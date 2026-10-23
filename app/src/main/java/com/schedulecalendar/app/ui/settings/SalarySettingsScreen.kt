// app/src/main/java/com/schedulecalendar/app/ui/settings/SalarySettingsScreen.kt
package com.schedulecalendar.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.domain.model.SalaryConfig
import com.schedulecalendar.app.ui.component.ScheduleTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalarySettingsScreen(
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
        topBar = { ScheduleTopBar("薪资设置", onBack = { navController.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        SalaryConfigSection(
            config = state.salaryConfig,
            profile = profile,
            onSave = { cfg, effectiveFrom -> vm.saveSalaryAsProfile(cfg, effectiveFrom) },
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun SalaryConfigSection(
    config: SalaryConfig,
    profile: com.schedulecalendar.app.domain.model.CalcProfile?,
    onSave: (SalaryConfig, String) -> Unit,
    modifier: Modifier = Modifier
) {
    // ── 标准工时制字段 ──
    var baseSalary   by remember(config) { mutableStateOf(config.baseSalary.toInputString()) }
    var basePerf     by remember(config) { mutableStateOf(config.basePerformance.toInputString()) }
    var normalRate   by remember(config) { mutableStateOf(config.normalRate.toInputString()) }
    var otRate       by remember(config) { mutableStateOf(config.overtimeRate.toInputString()) }
    var wkndRate     by remember(config) { mutableStateOf(config.weekendRate.toInputString()) }
    var holRate      by remember(config) { mutableStateOf(config.holidayRate.toInputString()) }
    var insurance    by remember(config) { mutableStateOf(config.socialInsurance.toInputString()) }
    var housingFund  by remember(config) { mutableStateOf(config.housingFundDeduction.toInputString()) }

    fun buildConfig() = SalaryConfig(
        baseSalary          = baseSalary.toDoubleOrNull()   ?: 0.0,
        basePerformance     = basePerf.toDoubleOrNull()     ?: 0.0,
        normalRate          = normalRate.toDoubleOrNull()   ?: 0.0,
        overtimeRate        = otRate.toDoubleOrNull()       ?: 0.0,
        weekendRate         = wkndRate.toDoubleOrNull()     ?: 0.0,
        holidayRate         = holRate.toDoubleOrNull()      ?: 0.0,
        socialInsurance     = insurance.toDoubleOrNull()    ?: 0.0,
        housingFundDeduction = housingFund.toDoubleOrNull() ?: 0.0
    )

    // 生效日期：默认今天，可切到下月 1 日（工资月结场景）
    var effectiveFrom by remember { mutableStateOf(effectiveDateOptions().first()) }
    // 有改动才允许保存（避免误点生成空档案）
    val dirty = buildConfig() != config

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── 影响提示：这些参数参与历史工时/薪资计算 ──
        Text(
            "ⓘ 修改会立即生效，并追溯影响历史月份的工时与薪资（历史排班会按新参数重新计算）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )

        // ── 当前生效口径（只读）+ 生效日期选择 ──
        CurrentProfileCard(profile)
        EffectiveDateRow(selected = effectiveFrom, onSelect = { effectiveFrom = it })

        // ── 标准工时制 ──
        Text("标准工时制", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)

        ProtectedNumField("基础底薪 (元/月)", baseSalary)   { baseSalary  = it }
        ProtectedNumField("基础绩效 (元/月)", basePerf)     { basePerf    = it }
        ProtectedNumField("正常时薪 (元/时)", normalRate)   { normalRate  = it }
        ProtectedNumField("加班时薪 (元/时)", otRate)       { otRate      = it }
        ProtectedNumField("周末时薪 (元/时)", wkndRate)     { wkndRate    = it }
        ProtectedNumField("节假日时薪 (元/时)", holRate)    { holRate     = it }
        ProtectedNumField("社保扣款 (元/月)", insurance)    { insurance   = it }
        ProtectedNumField("公积金扣款 (元/月)", housingFund){ housingFund = it }

        // ── 保存：生成新口径档案（生效日之前的历史不受影响）──
        ProfileSaveBar(
            enabled = dirty,
            onSave = { onSave(buildConfig(), effectiveFrom) },
            onReset = {
                baseSalary   = config.baseSalary.toInputString()
                basePerf     = config.basePerformance.toInputString()
                normalRate   = config.normalRate.toInputString()
                otRate       = config.overtimeRate.toInputString()
                wkndRate     = config.weekendRate.toInputString()
                holRate      = config.holidayRate.toInputString()
                insurance    = config.socialInsurance.toInputString()
                housingFund  = config.housingFundDeduction.toInputString()
            }
        )

        // 底部间距，避免被导航栏遮挡
        Spacer(Modifier.height(16.dp))
    }
}
