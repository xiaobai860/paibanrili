// app/src/main/java/com/schedulecalendar/app/ui/detail/ScheduleDetailScreen.kt
package com.schedulecalendar.app.ui.detail

import android.graphics.Color as AColor
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import com.schedulecalendar.app.ui.component.TimePickerField
import com.schedulecalendar.app.ui.theme.HolidayRed
import com.schedulecalendar.app.domain.model.LunarCalendar
import com.schedulecalendar.app.domain.model.HolidayData
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
fun ScheduleDetailScreen(
    navController: NavController,
    vm: ScheduleDetailViewModel = hiltViewModel()
) {
    val state       by vm.state.collectAsStateWithLifecycle()
    val snackbar     = remember { SnackbarHostState() }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ScheduleTopBar(
                title   = "排班编辑",
                onBack  = { navController.popBackStack() },
                actions = {
                    if (record?.shiftId != null) {
                        TextButton(onClick = vm::deleteRecord) {
                            Text("清除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top   = pad.calculateTopPadding() + 8.dp,
                bottom = pad.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // ── 日期信息卡片 ──────────────────────────────────────────
            item {
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
            }

            // ── 班次选择 ──────────────────────────────────────────────
            item {
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
                            if (!isRestShift)
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
            }

            // ── 实际打卡时间（非休息班次才显示） ────────────────────────
            if (selectedShift != null && !isRestShift) {
                item {
                    SectionLabel("实际打卡时间（可选）")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimePickerField(
                            time         = record?.actualStartTime ?: "",
                            onTimeChange = vm::setActualStart,
                            label        = "实际上班",
                            modifier     = Modifier.weight(1f)
                        )
                        TimePickerField(
                            time         = record?.actualEndTime ?: "",
                            onTimeChange = vm::setActualEnd,
                            label        = "实际下班",
                            modifier     = Modifier.weight(1f)
                        )
                    }
                }

            }

            // ── 计薪方式 ──────────────────────────────────────────────
            if (selectedShift != null && !isRestShift) {
                item {
                    SectionLabel("计薪方式")
                    if (record?.salaryMode == null) {
                        // 自动模式：显示只读标签
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Text(
                                text = "自动（${when {
                                    state.previewHours > 0 -> "工作日"
                                    else -> "按日期判断"
                                }}）",
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
            }

            // ── 附加状态 ──────────────────────────────────────────────
            if (state.shiftStatuses.isNotEmpty() && selectedShift != null) {
                item {
                    SectionLabel("附加状态")
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.shiftStatuses.forEach { status ->
                            val appliedSt = if (record?.appliedStatus?.statusId == status.id) record?.appliedStatus else null
                            val applied   = appliedSt != null
                            StatusRow(
                                status    = status,
                                applied   = applied,
                                startTime = appliedSt?.startTime,
                                endTime   = appliedSt?.endTime,
                                onToggle  = { vm.toggleStatus(status.id, null, null) },
                                onEditTime = { showStatusEditor = status.id }
                            )
                        }
                    }
                }
            }

            // ── 补贴/扣款 ─────────────────────────────────────────────
            if (state.extraItems.isNotEmpty()) {
                item {
                    SectionLabel("补贴 / 扣款")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.extraItems.forEach { item ->
                            val checked = record?.extraItemIds?.contains(item.id) == true
                            ExtraItemRow(item, checked) { vm.toggleExtraItem(item.id) }
                        }
                    }
                }
            }

            // ── 备注 ──────────────────────────────────────────────────
            item {
                SectionLabel("备注（可选）")
                OutlinedTextField(
                    value         = record?.remark ?: "",
                    onValueChange = vm::setRemark,
                    placeholder   = { Text("输入备注信息…") },
                    modifier      = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 120.dp).wrapContentHeight(),
                    maxLines      = Int.MAX_VALUE,
                    minLines      = 2
                )
            }

            // ── 保存按钮 ──────────────────────────────────────────────
            item {
                Button(
                    onClick  = vm::save,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled  = record?.shiftId != null,
                    shape    = MaterialTheme.shapes.medium
                ) { Text("保存排班", style = MaterialTheme.typography.titleSmall) }
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

    // ── 状态时间段编辑弹窗 ────────────────────────────────────────────
    showStatusEditor?.let { sid ->
        val appliedSt = if (record?.appliedStatus?.statusId == sid) record?.appliedStatus else null
        StatusTimeDialog(
            startTime = appliedSt?.startTime ?: "",
            endTime   = appliedSt?.endTime   ?: "",
            onConfirm = { s, e -> vm.updateStatusTime(sid, s.ifBlank { null }, e.ifBlank { null }); showStatusEditor = null },
            onDismiss = { showStatusEditor = null }
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
private fun StatusRow(
    status: ShiftStatus,
    applied: Boolean,
    startTime: String?,
    endTime: String?,
    onToggle: () -> Unit,
    onEditTime: () -> Unit
) {
    val bg = if (applied) MaterialTheme.colorScheme.secondaryContainer
             else MaterialTheme.colorScheme.surfaceVariant
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable { onToggle() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(safeColor(status.color)))
        Spacer(Modifier.width(10.dp))
        Text(status.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (applied) {
            val timeLabel = if (startTime != null && endTime != null) "$startTime–$endTime" else "全天"
            TextButton(
                onClick      = onEditTime,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(timeLabel, style = MaterialTheme.typography.labelSmall)
            }
        }
        Checkbox(checked = applied, onCheckedChange = { onToggle() })
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
        val typeColor = if (item.type == "subsidy") Color(0xFF059669) else Color(0xFFDC2626)
        Surface(shape = RoundedCornerShape(4.dp), color = typeColor.copy(alpha = 0.12f)) {
            Text(
                text     = if (item.type == "subsidy") "补" else "扣",
                style    = MaterialTheme.typography.labelSmall,
                color    = typeColor,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            text  = if (item.type == "subsidy") "+¥${item.amount}" else "-¥${item.amount}",
            color = typeColor,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.width(8.dp))
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun StatusTimeDialog(
    startTime: String,
    endTime: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var s by remember { mutableStateOf(startTime) }
    var e by remember { mutableStateOf(endTime) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("设置状态时间段") },
        text    = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimePickerField(
                    time         = s,
                    onTimeChange = { s = it },
                    label        = "开始",
                    modifier     = Modifier.weight(1f)
                )
                TimePickerField(
                    time         = e,
                    onTimeChange = { e = it },
                    label        = "结束",
                    modifier     = Modifier.weight(1f)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(s, e) }) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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
                        if (shift.startTime.isNotEmpty() && shift.builtInType != "rest")
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
    runCatching { Color(AColor.parseColor(hex)) }.getOrElse { Color.Gray }
