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
            title  = if (shiftId != null) "\u7f16\u8f91\u73ed\u6b21" else "\u65b0\u589e\u73ed\u6b21",
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
            // \u540d\u79f0
            item {
                OutlinedTextField(
                    value         = s.name,
                    onValueChange = { vm.update { copy(name = it) } },
                    label         = { Text("\u73ed\u6b21\u540d\u79f0 *") },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    colors        = stableLabelColors()
                )
                if (errorMsg == "\u73ed\u6b21\u540d\u79f0\u5df2\u5b58\u5728\uff0c\u8bf7\u4fee\u6539\u540e\u4fdd\u5b58" || errorMsg == "\u73ed\u6b21\u540d\u79f0\u4e0d\u80fd\u4e3a\u7a7a") {
                    Text(
                        text  = errorMsg ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // \u4e0a\u4e0b\u73ed\u65f6\u95f4
            item {
                SectionLabel("\u5de5\u4f5c\u65f6\u95f4")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimePickerField(
                        time           = s.startTime,
                        onTimeChange   = { vm.update { copy(startTime = it) }; errorMsg = null },
                        label          = "\u4e0a\u73ed\u65f6\u95f4 *",
                        modifier       = Modifier.weight(1f)
                    )
                    TimePickerField(
                        time           = s.endTime,
                        onTimeChange   = { vm.update { copy(endTime = it) }; errorMsg = null },
                        label          = "\u4e0b\u73ed\u65f6\u95f4 *",
                        modifier       = Modifier.weight(1f)
                    )
                }
                if (errorMsg == "\u8bf7\u8f93\u5165\u5b8c\u6574\u7684\u4e0a\u4e0b\u73ed\u65f6\u95f4") {
                    Text(
                        text  = errorMsg ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // \u65f6\u957f\u4fe1\u606f\u9884\u89c8\u677f\u5757
            item {
                val totalHours = CalcUtils.calcHourDiff(s.startTime, s.endTime)
                // 使用 CalcUtils.calcGlobalBreakHours 正确计算班次与不计入时段的交集
                val breakHours = if (s.startTime.isNotEmpty() && s.endTime.isNotEmpty()) {
                    CalcUtils.calcGlobalBreakHours(s.startTime, s.endTime, s.allBreaks)
                } else 0.0
                val actualHours = maxOf(0.0, totalHours - breakHours)

                SectionLabel("\u65f6\u957f\u9884\u89c8")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DurationInfoRow("\u603b\u65f6\u957f", "${String.format("%.1f", totalHours)} \u5c0f\u65f6")
                        DurationInfoRow("\u4f11\u606f/\u7528\u9910\u65f6\u95f4", "${String.format("%.1f", breakHours)} \u5c0f\u65f6")
                        HorizontalDivider()
                        DurationInfoRow("\u5b9e\u9645\u5de5\u65f6", "${String.format("%.1f", actualHours)} \u5c0f\u65f6",
                            valueColor = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // \u989c\u8272\u9009\u62e9 + \u73ed\u6b21\u6807\u7b7e\u9884\u89c8
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // \u73ed\u6b21\u989c\u8272\u6807\u7b7e + \u9884\u89c8
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "\u73ed\u6b21\u989c\u8272",
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
                                text = s.name.ifBlank { "\u73ed\u6b21" },
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

            // \u9ed8\u8ba4\u5173\u8054\u8865\u8d34/\u6263\u6b3e\u9879
            if (s.allExtraItems.isNotEmpty()) {
                item {
                    SectionLabel("\u9ed8\u8ba4\u5173\u8054\u8865\u8d34/\u6263\u6b3e\u9879")
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

            // \u901a\u7528\u9519\u8bef\u63d0\u793a\uff08\u975e\u540d\u79f0\u7c7b\u9519\u8bef\uff09
            if (errorMsg != null && errorMsg != "\u73ed\u6b21\u540d\u79f0\u5df2\u5b58\u5728\uff0c\u8bf7\u4fee\u6539\u540e\u4fdd\u5b58" && errorMsg != "\u73ed\u6b21\u540d\u79f0\u4e0d\u80fd\u4e3a\u7a7a" && errorMsg != "\u8bf7\u8f93\u5165\u5b8c\u6574\u7684\u4e0a\u4e0b\u73ed\u65f6\u95f4") {
                item {
                    Text(errorMsg ?: "", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            // \u4fdd\u5b58\u6309\u94ae
            item {
                Button(
                    onClick  = vm::save,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape    = MaterialTheme.shapes.medium
                ) { Text("\u4fdd\u5b58\u73ed\u6b21", style = MaterialTheme.typography.titleSmall) }
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
