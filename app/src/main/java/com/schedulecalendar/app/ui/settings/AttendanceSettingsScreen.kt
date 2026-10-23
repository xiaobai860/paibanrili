// app/src/main/java/com/schedulecalendar/app/ui/settings/AttendanceSettingsScreen.kt
package com.schedulecalendar.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.domain.model.AttendConfig
import com.schedulecalendar.app.ui.component.ScheduleTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceSettingsScreen(
    navController: NavController,
    vm: SettingsViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { ScheduleTopBar("考勤设置", onBack = { navController.popBackStack() }) }
    ) { padding ->
        AttendConfigSection(
            config = state.attendConfig,
            onSave = vm::saveAttendConfig,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun AttendConfigSection(
    config: AttendConfig,
    onSave: (AttendConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var granMin      by remember(config) { mutableIntStateOf(config.overtimeGranMin) }
    var lateTol      by remember(config) { mutableStateOf(config.lateToleranceMin.toInputString()) }
    var earlyTol     by remember(config) { mutableStateOf(config.earlyLeaveToleranceMin.toInputString()) }
    var lateAlert    by remember(config) { mutableStateOf(config.lateAlertCount.toInputString()) }
    var earlyAlert   by remember(config) { mutableStateOf(config.earlyLeaveAlertCount.toInputString()) }
    var stdHours     by remember(config) { mutableStateOf(config.normalWorkHoursPerDay.toInputString()) }
    var lateDeduct   by remember(config) { mutableStateOf(config.lateDeductionPerMin.toInputString()) }
    var earlyDeduct  by remember(config) { mutableStateOf(config.earlyLeaveDeductionPerMin.toInputString()) }

    fun save() = onSave(AttendConfig(
        // 加班粒度作为除数使用，必须 ≥1，否则恢复旧备份/手输 0 会在取整时除零崩溃
        overtimeGranMin           = granMin.coerceIn(1, 120),
        lateToleranceMin          = lateTol.toIntOrNull()?.coerceIn(0, 120) ?: 0,
        earlyLeaveToleranceMin    = earlyTol.toIntOrNull()?.coerceIn(0, 120) ?: 0,
        lateAlertCount            = lateAlert.toIntOrNull()                ?: 0,
        earlyLeaveAlertCount      = earlyAlert.toIntOrNull()               ?: 0,
        normalWorkHoursPerDay     = stdHours.toDoubleOrNull()              ?: 0.0,
        lateDeductionPerMin       = lateDeduct.toDoubleOrNull()            ?: 0.0,
        earlyLeaveDeductionPerMin = earlyDeduct.toDoubleOrNull()           ?: 0.0
    ))

    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── 影响提示：这些参数参与历史工时/薪资计算 ──
        Text(
            "ⓘ 修改会立即生效，并追溯影响历史月份的工时与薪资（历史排班会按新参数重新计算）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )

        // ── 加班粒度：单选按钮组 ──
        Text("加班粒度", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15 to "分钟", 30 to "分钟", 60 to "分钟").forEach { (value, unit) ->
                FilterChip(
                    selected = granMin == value,
                    onClick  = { granMin = value; save() },
                    label    = { Text("$value$unit") }
                )
            }
        }

        ProtectedNumField("迟到容忍（分钟）", lateTol, "0~120") { lateTol = it; save() }
        ProtectedNumField("早退容忍（分钟）", earlyTol, "0~120") { earlyTol = it; save() }

        ProtectedNumField("迟到提醒阈值（次）", lateAlert, "0")  { lateAlert   = it; save() }
        ProtectedNumField("早退提醒阈值（次）", earlyAlert, "0") { earlyAlert  = it; save() }
        Text("提醒阈值 = 允许的次数，超过才在统计页警示；默认 0 → 出现 1 次即警示",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp))

        ProtectedNumField("日标准工时（小时）", stdHours, "请输入日标准工时") { stdHours = it; save() }
        ProtectedNumField("迟到扣款（元/分钟）", lateDeduct)     { lateDeduct  = it; save() }
        ProtectedNumField("早退扣款（元/分钟）", earlyDeduct)    { earlyDeduct = it; save() }
    }
}
