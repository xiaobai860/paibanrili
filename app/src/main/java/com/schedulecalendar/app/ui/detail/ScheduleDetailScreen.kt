// app/src/main/java/com/schedulecalendar/app/ui/detail/ScheduleDetailScreen.kt
package com.schedulecalendar.app.ui.detail
import android.util.Log
import androidx.compose.ui.res.stringResource
import com.schedulecalendar.app.BuildConfig
import com.schedulecalendar.app.R
import com.schedulecalendar.app.ui.util.currentLocale

import androidx.core.graphics.toColorInt
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleDetailScreen(
    navController: NavController,
    vm: ScheduleDetailViewModel = hiltViewModel()
) {
    val state       by vm.state.collectAsStateWithLifecycle()
    if (BuildConfig.DEBUG) Log.e("WBD", "detail: composed date=" + state.date)
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

    // 返回键分级：弹窗/对话框打开时第一次返回只关闭弹窗，避免「sheet 关闭动画 + 导航返回转场」
    // 叠加在 ColorOS 上偶发渲染冻结（白屏 1-2 秒自愈）；全部关闭后再按返回才 popBackStack
    BackHandler(
        enabled = showShiftPicker || showStatusPicker || showStatusEditor != null || timeDialogConfig != null
    ) {
        if (BuildConfig.DEBUG) Log.e("WBD", "detail: back -> close overlays")
        showShiftPicker  = false
        showStatusPicker = false
        showStatusEditor = null
        timeDialogConfig = null
    }

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
        val labels = arrayOf(
            stringResource(R.string.week_monday), stringResource(R.string.week_tuesday),
            stringResource(R.string.week_wednesday), stringResource(R.string.week_thursday),
            stringResource(R.string.week_friday), stringResource(R.string.week_saturday),
            stringResource(R.string.week_sunday)
        )
        labels[dow.value - 1]   // DayOfWeek.MONDAY=1 … SUNDAY=7
    } else ""
    val selectedShift = record?.shiftId?.let { id -> state.shifts.find { it.id == id } }
    val isRestShift   = selectedShift?.builtInType == "rest"
    val isSwapShift   = selectedShift?.builtInType == "swap"
    val isRestOrSwap  = isRestShift || isSwapShift
    // 休息/调休班次选择了任意附加状态（不论是否带时间段）时显示计薪方式
    val hasAppliedStatus = isRestOrSwap && record.appliedStatus != null

    val visibleStatuses = if (isRestOrSwap) {
        state.shiftStatuses.filter { s -> s.id != BUILTIN_STATUS_SWAP && s.id != BUILTIN_STATUS_LEAVE }
    } else state.shiftStatuses

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ScheduleTopBar(
                title   = stringResource(R.string.detail_title),
                onBack  = { if (BuildConfig.DEBUG) Log.e("WBD", "detail: topbar back"); navController.popBackStack() },
                actions = {
                    // 清除按钮（左侧）
                    if (record?.shiftId != null) {
                        TextButton(onClick = vm::deleteRecord) {
                            Text(stringResource(R.string.common_clear), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    // 保存按钮（右侧）
                    TextButton(onClick = vm::save) {
                        Text(stringResource(R.string.detail_save), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { pad ->
        Column(
            // 6dp 只是「区块之间」的基础间距；标题自身再补 8dp 上边距，
            // 于是 区块间隔 = 14dp、标题↔自己的内容 = 6+2 = 8dp（原来两者都是 20dp，看不出层级）
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
            SectionLabel(stringResource(R.string.detail_shift))
            Row(
                Modifier.fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { if (BuildConfig.DEBUG) Log.e("WBD", "detail: click shift row"); showShiftPicker = true }
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    // 与附加状态行高一致（min 52dp）
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 16.dp),
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
                    Text(stringResource(R.string.detail_shift_hint), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── 实际打卡时间（非休息/调休班次才显示） ────────────────────────
            if (selectedShift != null && !isRestOrSwap) {
                SectionLabel(stringResource(R.string.detail_actual_time))
                // stringResource 只能在 @Composable 上下文中调用，而 onRequestDialog 是普通回调，
                // 故先提升为局部变量，供 TimePickerField 与其回调复用。
                val actualStartLabel = stringResource(R.string.detail_actual_start)
                val actualEndLabel   = stringResource(R.string.detail_actual_end)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 实际上班 + 清除按钮
                    Row(Modifier.weight(1f).height(IntrinsicSize.Min)) {
                        TimePickerField(
                            time         = record.actualStartTime ?: "",
                            onTimeChange = vm::setActualStart,
                            label        = actualStartLabel,
                            defaultTime  = selectedShift.startTime,
                            onRequestDialog = {
                                timeDialogConfig = TimeDialogConfig(
                                    label = actualStartLabel,
                                    currentTime = record.actualStartTime ?: "",
                                    defaultTime = selectedShift.startTime,
                                    onConfirm = vm::setActualStart
                                )
                            },
                            modifier     = Modifier.weight(1f)
                        )
                        if (record.actualStartTime != null) {
                            Box(
                                modifier = Modifier
                                    .height(54.dp)
                                    .width(36.dp)
                                    .align(Alignment.Bottom)
                                    .clickable { vm.setActualStart("") },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, stringResource(R.string.detail_clear_actual_start), modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    // 实际下班 + 清除按钮
                    Row(Modifier.weight(1f).height(IntrinsicSize.Min)) {
                        TimePickerField(
                            time         = record.actualEndTime ?: "",
                            onTimeChange = vm::setActualEnd,
                            label        = actualEndLabel,
                            defaultTime  = selectedShift.endTime,
                            onRequestDialog = {
                                timeDialogConfig = TimeDialogConfig(
                                    label = actualEndLabel,
                                    currentTime = record.actualEndTime ?: "",
                                    defaultTime = selectedShift.endTime,
                                    onConfirm = vm::setActualEnd
                                )
                            },
                            modifier     = Modifier.weight(1f)
                        )
                        if (record.actualEndTime != null) {
                            Box(
                                modifier = Modifier
                                    .height(54.dp)
                                    .width(36.dp)
                                    .align(Alignment.Bottom)
                                    .clickable { vm.setActualEnd("") },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, stringResource(R.string.detail_clear_actual_end), modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // ── 附加状态（与班次一致：单行选择器） ──────────────────────
            if (visibleStatuses.isNotEmpty() && selectedShift != null) {
                val appliedSt = record.appliedStatus
                val appliedStatus = appliedSt?.let { st -> visibleStatuses.find { it.id == st.statusId } }
                // 「计为加班」开关固定在标题行最右侧，需同时满足三个条件才显示：
                // 1. 已选附加状态；
                // 2. 该状态不是内置请假/调休 —— 它们本身没有加班语义；
                // 3. 班次是「**无时段**班次」（内置休息/调休/请假）—— 这类班次不排具体上下班时间，
                //    工时完全由附加状态的时间段决定，加班只能靠这个开关体现，所以**只在这种情况下显示**；
                //    有时段的普通班次有自己的上下班时间与正常班阈值，不需要它。
                val shiftHasNoTime = selectedShift.startTime.isNullOrEmpty() ||
                        selectedShift.endTime.isNullOrEmpty()
                val st = appliedSt?.takeIf {
                    shiftHasNoTime &&
                    it.statusId != BUILTIN_STATUS_LEAVE && it.statusId != BUILTIN_STATUS_SWAP
                }
                val overtimeToggle: (@Composable () -> Unit)? = if (st != null) {
                    { OvertimeToggle(checked = st.isOvertime) { vm.setOvertime(it) } }
                } else null
                SectionLabel(
                    text     = stringResource(R.string.detail_status),
                    trailing = overtimeToggle
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (appliedStatus != null) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        // 点击行主体 / > 图标 → 弹出附加状态选择页（与选择班次一致）
                        .clickable { if (BuildConfig.DEBUG) Log.e("WBD", "detail: click status row"); showStatusPicker = true }
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
                                stringResource(R.string.detail_no_status),
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
                                else -> stringResource(R.string.detail_all_day)
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
                        Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.detail_pick_status),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── 补贴/扣款 ─────────────────────────────────────────────
            if (state.extraItems.isNotEmpty()) {
                SectionLabel(stringResource(R.string.detail_extra))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.extraItems.forEach { item ->
                        val checked = record?.extraItemIds?.contains(item.id) == true
                        ExtraItemRow(item, checked) { vm.toggleExtraItem(item.id) }
                    }
                }
            }

            // ── 备注 ──────────────────────────────────────────────────
            SectionLabel(stringResource(R.string.detail_remark))
            ImeAdaptiveOutlinedTextField(
                value         = record?.remark ?: "",
                onValueChange = vm::setRemark,
                placeholder   = { Text(stringResource(R.string.detail_remark_hint)) },
                modifier      = Modifier.fillMaxWidth(),
                maxLines      = Int.MAX_VALUE,
                minLines      = 2,
                scrollState   = scrollState
            )

            // ── 计薪方式 ──────────────────────────────────────────────
            if (selectedShift != null && (!isRestOrSwap || hasAppliedStatus)) {
                SectionLabel(stringResource(R.string.detail_salary_mode))
                val modes = listOf(
                    SalaryMode.NORMAL  to stringResource(R.string.detail_mode_normal),
                    SalaryMode.WEEKEND to stringResource(R.string.detail_mode_weekend),
                    SalaryMode.HOLIDAY to stringResource(R.string.detail_mode_holiday)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement   = Arrangement.spacedBy(8.dp)
                ) {
                    // 自动模式按钮（常驻）：选中时显示「自动-工作日/周末/节假日」随当天规则动态变化；
                    // 选择其它模式后文案变为「自动计算」，提示点击可恢复自动
                    val isAuto = record.salaryMode == null
                    FilterChip(
                        selected = isAuto,
                        onClick  = { vm.setSalaryMode(null) },
                        label    = {
                            Text(
                                if (isAuto) "自动-${state.autoModeLabel.ifEmpty { stringResource(R.string.detail_auto_by_date) }}"
                                else stringResource(R.string.detail_auto_calc),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )
                    modes.forEach { (mode, label) ->
                        val selected = record.salaryMode == mode
                        FilterChip(
                            selected = selected,
                            onClick  = { vm.setSalaryMode(mode) },
                            label    = { Text(label, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
            }

            // ── 工时与薪资明细 ──────────────────────────────────────────
            if (selectedShift != null && state.previewHours > 0) {
                SectionLabel(stringResource(R.string.detail_hours_salary))
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
                            Text(stringResource(R.string.detail_normal_hours), style = MaterialTheme.typography.bodyMedium)
                            Text("${CalcUtils.fmtHours(state.detailNormalHours)}h",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                        }
                        if (state.detailOvertimeHours > 0) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.detail_overtime_hours), style = MaterialTheme.typography.bodyMedium)
                                Text("${CalcUtils.fmtHours(state.detailOvertimeHours)}h",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                            }
                        }
                        HorizontalDivider()
                        // 薪资行
                        if (state.detailNormalSalary > 0) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.detail_normal_income), style = MaterialTheme.typography.bodyMedium)
                                Text("¥${String.format(currentLocale(), "%.0f", state.detailNormalSalary)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                            }
                        }
                        if (state.detailOvertimeSalary > 0) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.detail_overtime_income), style = MaterialTheme.typography.bodyMedium)
                                Text("¥${String.format(currentLocale(), "%.0f", state.detailOvertimeSalary)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                            }
                        }
                        if (state.detailTotalSalary > 0) {
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.detail_total_income), style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold)
                                Text("¥${String.format(currentLocale(), "%.0f", state.detailTotalSalary)}",
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
            onDismiss = { if (BuildConfig.DEBUG) Log.e("WBD", "detail: shift sheet dismiss"); showShiftPicker = false }
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
            onDismiss = { if (BuildConfig.DEBUG) Log.e("WBD", "detail: status sheet dismiss"); showStatusPicker = false }
        )
    }

    // ── 状态时间段编辑弹窗 ────────────────────────────────────────────
    showStatusEditor?.let { sid ->
        val appliedSt = if (record?.appliedStatus?.statusId == sid) record.appliedStatus else null
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
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { timeDialogConfig = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

// ── 辅助组件 ──────────────────────────────────────────────────────────────────

/**
 * 区块标题。可选 [trailing]：把控件紧贴标题行**最右侧**（如附加状态标题右边的「计为加班」开关）。
 *
 * 间距约定（与父级 `Arrangement.spacedBy(6.dp)` 配合）：
 * 标题 ↔ 自己的内容 = 8dp（spacedBy 6 + 本组件 bottom 2）；
 * 区块之间 = 8dp（本组件 top）+ 6dp = 14dp。
 * 即「标题贴近内容、区块之间留白」，形成层级感。
 */
@Composable
private fun SectionLabel(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier          = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text  = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/**
 * 标题行最右侧的「计为加班」开关（紧凑形态，整行可点）。
 * 勾选后该附加状态的时间段按当天「计薪方式」归类（工作日→加班工时、周末→周末工时、节假日→节假日工时）。
 *
 * 指示方块自绘 16dp，而不用 M3 [Checkbox]：后者自带 20dp 方块 + 48dp 最小交互区，
 * 放在标题行里相对 14sp 的标题明显偏大。
 */
@Composable
private fun OvertimeToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier          = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier         = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    imageVector        = Icons.Default.Check,
                    contentDescription = null,
                    modifier           = Modifier.size(12.dp),
                    tint               = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        Spacer(Modifier.width(5.dp))
        Text(
            stringResource(R.string.detail_count_as_overtime),
            style = MaterialTheme.typography.labelLarge,
            color = if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExtraItemRow(item: ExtraItem, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { onToggle() }
            // 与附加状态行高一致（min 52dp）
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val typeColor = if (item.type == "allowance") AllowanceGreen else DeductionRed
        Surface(shape = RoundedCornerShape(4.dp), color = typeColor.copy(alpha = 0.12f)) {
            Text(
                text     = if (item.type == "allowance") stringResource(R.string.detail_extra_allowance)
                           else stringResource(R.string.detail_extra_deduction),
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
        title   = { Text(stringResource(R.string.detail_status_time_title)) },
        text    = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimePickerField(
                        time         = s,
                        onTimeChange = { s = it },
                        label        = stringResource(R.string.detail_start),
                        defaultTime  = defaultStartTime,
                        onRequestDialog = { editingField = "start" },
                        modifier     = Modifier.weight(1f)
                    )
                    TimePickerField(
                        time         = e,
                        onTimeChange = { e = it },
                        label        = stringResource(R.string.detail_end),
                        defaultTime  = defaultEndTime,
                        onRequestDialog = { editingField = "end" },
                        modifier     = Modifier.weight(1f)
                    )
                }
                if (timeWarning) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.detail_status_time_overflow),
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
                        Text(stringResource(R.string.detail_clear_time), color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Row {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = {
                        s = clampAndSet(s, true)
                        e = clampAndSet(e, false)
                        onConfirm(s, e)
                    }) { Text(stringResource(R.string.common_ok)) }
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
            title = { Text(if (isStart) stringResource(R.string.detail_start_time) else stringResource(R.string.detail_end_time),
                style = MaterialTheme.typography.titleMedium) },
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
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { editingField = null }) { Text(stringResource(R.string.common_cancel)) }
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
            Text(stringResource(R.string.detail_pick_status), style = MaterialTheme.typography.titleMedium,
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
                    Text(stringResource(R.string.detail_no_status_option), style = MaterialTheme.typography.bodyLarge,
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
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(st.name, style = MaterialTheme.typography.bodyLarge)
                        if (st.builtIn) {
                            Spacer(Modifier.width(8.dp))
                            Surface(shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(stringResource(R.string.common_builtin), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                    if (selected) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_selected),
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
            Text(stringResource(R.string.detail_shift_picker_title), style = MaterialTheme.typography.titleMedium,
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(shift.name, style = MaterialTheme.typography.bodyLarge)
                            if (shift.builtIn) {
                                Spacer(Modifier.width(8.dp))
                                Surface(shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(stringResource(R.string.common_builtin), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                        }
                        if (shift.startTime.isNotEmpty() && shift.builtInType != "rest" && shift.builtInType != "swap")
                            Text("${shift.startTime} – ${shift.endTime}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

fun safeColor(hex: String): Color =
    runCatching { Color(hex.toColorInt()) }.getOrElse { Color.Gray }
