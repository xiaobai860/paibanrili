// app/src/main/java/com/schedulecalendar/app/ui/settings/AttendanceSettingsScreen.kt
package com.schedulecalendar.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    val profile by vm.currentProfile.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // 保存结果提示：明确告诉用户「生成了新口径、历史不受影响」
    LaunchedEffect(Unit) {
        vm.uiEvent.collect { e ->
            when (e) {
                is SettingsUiEvent.ProfileCreated -> snackbar.showSnackbar(
                    "已创建新口径「${e.name}」，自 ${e.effectiveFrom} 起生效；之前的记录不变"
                )
                is SettingsUiEvent.ShowError -> snackbar.showSnackbar(e.msg)
                else -> Unit
            }
        }
    }

    Scaffold(
        topBar = { ScheduleTopBar("考勤设置", onBack = { navController.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        AttendConfigSection(
            config = state.attendConfig,
            profile = profile,
            onSave = { cfg, effectiveFrom -> vm.saveAttendAsProfile(cfg, effectiveFrom) },
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun AttendConfigSection(
    config: AttendConfig,
    profile: com.schedulecalendar.app.domain.model.CalcProfile?,
    onSave: (AttendConfig, String) -> Unit,
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

    fun buildConfig() = AttendConfig(
        // 加班粒度作为除数使用，必须 ≥1，否则恢复旧备份/手输 0 会在取整时除零崩溃
        overtimeGranMin           = granMin.coerceIn(1, 120),
        lateToleranceMin          = lateTol.toIntOrNull()?.coerceIn(0, 120) ?: 0,
        earlyLeaveToleranceMin    = earlyTol.toIntOrNull()?.coerceIn(0, 120) ?: 0,
        lateAlertCount            = lateAlert.toIntOrNull()                ?: 0,
        earlyLeaveAlertCount      = earlyAlert.toIntOrNull()               ?: 0,
        normalWorkHoursPerDay     = stdHours.toDoubleOrNull()              ?: 0.0,
        lateDeductionPerMin       = lateDeduct.toDoubleOrNull()            ?: 0.0,
        earlyLeaveDeductionPerMin = earlyDeduct.toDoubleOrNull()           ?: 0.0
    )

    // 生效日期：默认今天，可切到下月 1 日（工资月结场景）
    var effectiveFrom by remember { mutableStateOf(effectiveDateOptions().first()) }
    // 有改动才允许保存（避免误点生成空档案）
    val dirty = buildConfig() != config

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── 当前生效口径（只读）+ 生效日期选择 ──
        CurrentProfileCard(profile)
        EffectiveDateRow(selected = effectiveFrom, onSelect = { effectiveFrom = it })

        // ── 加班粒度：单选按钮组 ──
        Text("加班粒度", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15 to "分钟", 30 to "分钟", 60 to "分钟").forEach { (value, unit) ->
                FilterChip(
                    selected = granMin == value,
                    onClick  = { granMin = value },
                    label    = { Text("$value$unit") }
                )
            }
        }

        ProtectedNumField("迟到容忍（分钟）", lateTol, "0~120") { lateTol = it }
        ProtectedNumField("早退容忍（分钟）", earlyTol, "0~120") { earlyTol = it }

        ProtectedNumField("迟到提醒阈值（次）", lateAlert, "0")  { lateAlert   = it }
        ProtectedNumField("早退提醒阈值（次）", earlyAlert, "0") { earlyAlert  = it }
        Text("提醒阈值 = 允许的次数，超过才在统计页警示；默认 0 → 出现 1 次即警示",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp))

        ProtectedNumField("日标准工时（小时）", stdHours, "请输入日标准工时") { stdHours = it }
        ProtectedNumField("迟到扣款（元/分钟）", lateDeduct)     { lateDeduct  = it }
        ProtectedNumField("早退扣款（元/分钟）", earlyDeduct)    { earlyDeduct = it }

        // ── 保存：生成新口径档案（生效日之前的历史不受影响）──
        ProfileSaveBar(
            enabled = dirty,
            onSave = { onSave(buildConfig(), effectiveFrom) },
            onReset = {
                granMin     = config.overtimeGranMin
                lateTol     = config.lateToleranceMin.toInputString()
                earlyTol    = config.earlyLeaveToleranceMin.toInputString()
                lateAlert   = config.lateAlertCount.toInputString()
                earlyAlert  = config.earlyLeaveAlertCount.toInputString()
                stdHours    = config.normalWorkHoursPerDay.toInputString()
                lateDeduct  = config.lateDeductionPerMin.toInputString()
                earlyDeduct = config.earlyLeaveDeductionPerMin.toInputString()
            }
        )
    }
}
