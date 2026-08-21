// app/src/main/java/com/schedulecalendar/app/ui/shifts/ShiftEditorScreen.kt
package com.schedulecalendar.app.ui.shifts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.toRoute
import com.schedulecalendar.app.domain.model.CalcUtils
import com.schedulecalendar.app.ui.component.ColorPicker
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import com.schedulecalendar.app.ui.component.TimePickerField
import com.schedulecalendar.app.ui.component.stableLabelColors
import com.schedulecalendar.app.ui.detail.safeColor
import com.schedulecalendar.app.ui.navigation.RouteShiftEditor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShiftEditorScreen(navController: NavController, vm: ShiftEditorViewModel = hiltViewModel()) {
    val s      by vm.state.collectAsStateWithLifecycle()
    val shiftId = navController.currentBackStackEntry?.toRoute<RouteShiftEditor>()?.shiftId
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(shiftId) { if (shiftId != null) vm.load(shiftId) }

    LaunchedEffect(Unit) {
        vm.uiEvent.collect { ev ->
            when (ev) {
                is ShiftEditorUiEvent.NavigateBack -> navController.popBackStack()
                is ShiftEditorUiEvent.ShowError    -> errorMsg = ev.msg
            }
        }
    }

    Scaffold(topBar = {
        ScheduleTopBar(
            title  = if (shiftId != null) "编辑班次" else "新增班次",
            onBack = { navController.popBackStack() }
        )
    }) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top   = pad.calculateTopPadding() + 8.dp,
                bottom = pad.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 名称
            item {
                OutlinedTextField(
                    value         = s.name,
                    onValueChange = { vm.update { copy(name = it) } },
                    label         = { Text("班次名称 *") },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    colors        = stableLabelColors()
                )
                if (errorMsg == "班次名称已存在，请修改后保存" || errorMsg == "班次名称不能为空") {
                    Text(
                        text  = errorMsg ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // 上下班时间
            item {
                SectionLabel("工作时间")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimePickerField(
                        time           = s.startTime,
                        onTimeChange   = { vm.update { copy(startTime = it) }; errorMsg = null },
                        label          = "上班时间 *",
                        modifier       = Modifier.weight(1f)
                    )
                    TimePickerField(
                        time           = s.endTime,
                        onTimeChange   = { vm.update { copy(endTime = it) }; errorMsg = null },
                        label          = "下班时间 *",
                        modifier       = Modifier.weight(1f)
                    )
                }
                if (errorMsg == "请输入完整的上下班时间") {
                    Text(
                        text  = errorMsg ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // 时长信息预览板块
            item {
                val totalHours = CalcUtils.calcHourDiff(s.startTime, s.endTime)
                // 使用 CalcUtils.calcGlobalBreakHours 正确计算班次与不计入时段的交集
                val breakHours = if (s.startTime.isNotEmpty() && s.endTime.isNotEmpty()) {
                    CalcUtils.calcGlobalBreakHours(s.startTime, s.endTime, s.allBreaks)
                } else 0.0
                val actualHours = maxOf(0.0, totalHours - breakHours)

                SectionLabel("时长预览")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DurationInfoRow("总时长", "${String.format(java.util.Locale.getDefault(), "%.1f", totalHours)} 小时")
                        DurationInfoRow("休息/用餐时间", "${String.format(java.util.Locale.getDefault(), "%.1f", breakHours)} 小时")
                        HorizontalDivider()
                        DurationInfoRow("实际工时", "${String.format(java.util.Locale.getDefault(), "%.1f", actualHours)} 小时",
                            valueColor = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // 颜色选择 + 班次标签预览
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 班次颜色标签 + 预览
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "班次颜色",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val previewColor = safeColor(s.color)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(previewColor)
                                .padding(horizontal = 6.dp, vertical = 0.dp)
                                .defaultMinSize(minHeight = MaterialTheme.typography.labelLarge.lineHeight.value.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = s.name.ifBlank { "班次" },
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                ColorPicker(selected = s.color, onSelect = { vm.update { copy(color = it) } })
            }

            // 默认关联补贴/扣款项
            if (s.allExtraItems.isNotEmpty()) {
                item {
                    SectionLabel("默认关联补贴/扣款项")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        s.allExtraItems.forEach { extra ->
                            val linked = extra.id in s.linkedExtraIds
                            val typeColor = if (extra.type == "allowance") Color(0xFF059669) else Color(0xFFDC2626)
                            FilterChip(
                                selected = linked,
                                onClick  = { vm.toggleExtraLink(extra.id) },
                                label    = {
                                    Text(
                                        text  = "${extra.name} ${if (extra.type == "allowance") "+\u00a5${extra.amount}" else "-\u00a5${extra.amount}"}",
                                        fontSize = 13.sp
                                    )
                                },
                                leadingIcon = {
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(typeColor)
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // 通用错误提示（非名称类错误）
            if (errorMsg != null && errorMsg != "班次名称已存在，请修改后保存" && errorMsg != "班次名称不能为空" && errorMsg != "请输入完整的上下班时间") {
                item {
                    Text(errorMsg ?: "", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            // 保存按钮
            item {
                Button(
                    onClick  = vm::save,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape    = MaterialTheme.shapes.medium
                ) { Text("保存班次", style = MaterialTheme.typography.titleSmall) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, required: Boolean = false) {
    Row(
        modifier = Modifier.padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (required) {
            Text(" *", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DurationInfoRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = valueColor)
    }
}
