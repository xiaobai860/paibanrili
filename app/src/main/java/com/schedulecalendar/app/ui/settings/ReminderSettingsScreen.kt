package com.schedulecalendar.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.ui.component.ScheduleTopBar

/**
 * 上下班提醒设置页面
 * 提供提醒启用/禁用、提醒方式、提醒内容、提前提醒时间等配置
 */
@Composable
fun ReminderSettingsScreen(
    navController: NavController,
    vm: ReminderSettingsViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ScheduleTopBar(
                title = "上下班提醒",
                onBack = { navController.popBackStack() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 总开关 ──────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "启用提醒",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "开启后将根据排班记录自动发送提醒通知",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = { vm.toggleEnabled() }
                    )
                }
            }

            if (state.enabled) {
                // ── 提醒方式 ──────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "提醒方式",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = state.method == "alarm",
                                onClick = { vm.setMethod("alarm") },
                                label = { Text("闹钟提醒") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = state.method == "calendar",
                                onClick = { vm.setMethod("calendar") },
                                label = { Text("日历提醒") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Text(
                            if (state.method == "alarm") "使用系统闹钟精确触发提醒"
                            else "通过创建日历事件并设置提醒来触发",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ── 提醒内容 ──────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "提醒内容",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = state.reminderClockIn,
                                onCheckedChange = { vm.toggleClockIn() }
                            )
                            Text("上班提醒", Modifier.weight(1f))
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = state.reminderClockOut,
                                onCheckedChange = { vm.toggleClockOut() }
                            )
                            Text("下班提醒", Modifier.weight(1f))
                        }
                    }
                }

                // ── 上班提前提醒时间 ──────────────────────────────
                if (state.reminderClockIn) {
                    AdvanceTimeCard(
                        title = "上班提前提醒",
                        selectedMinutes = state.clockInAdvanceMinutes,
                        onSelect = { vm.setClockInAdvanceMinutes(it) }
                    )
                }

                // ── 下班提前提醒时间 ──────────────────────────────
                if (state.reminderClockOut) {
                    AdvanceTimeCard(
                        title = "下班提前提醒",
                        selectedMinutes = state.clockOutAdvanceMinutes,
                        onSelect = { vm.setClockOutAdvanceMinutes(it) }
                    )
                }

                // ── 说明文字 ──────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Text(
                        "提醒触发时间 = 班次时间 - 提前时间\n" +
                                "如上班时间 09:00，提前 15 分钟提醒，则在 08:45 触发提醒。\n" +
                                "如果某天没有排班记录，该天不会触发提醒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * 提前提醒时间选择卡片
 */
@Composable
private fun AdvanceTimeCard(
    title: String,
    selectedMinutes: Int,
    onSelect: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            // 时间选项网格
            val rows = ADVANCE_TIME_OPTIONS.chunked(4)
            rows.forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowOptions.forEach { minutes ->
                        FilterChip(
                            selected = selectedMinutes == minutes,
                            onClick = { onSelect(minutes) },
                            label = {
                                Text(
                                    if (minutes >= 60) "${minutes / 60}小时" else "${minutes}分钟",
                                    fontSize = 13.sp
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // 填充空位
                    repeat(4 - rowOptions.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            Text(
                "当前设置：提前 ${if (selectedMinutes >= 60) "${selectedMinutes / 60}小时${if (selectedMinutes % 60 > 0) "${selectedMinutes % 60}分钟" else ""}" else "${selectedMinutes}分钟"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
