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
import androidx.hilt.navigation.compose.hiltViewModel
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

    Scaffold(
        topBar = { ScheduleTopBar("薪资设置", onBack = { navController.popBackStack() }) }
    ) { padding ->
        SalaryConfigSection(
            config = state.salaryConfig,
            onSave = vm::saveSalaryConfig,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun SalaryConfigSection(
    config: SalaryConfig,
    onSave: (SalaryConfig) -> Unit,
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

    fun save() = onSave(buildConfig())

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── 标准工时制 ──
        Text("标准工时制", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)

        ProtectedNumField("基础底薪 (元/月)", baseSalary)   { baseSalary  = it; save() }
        ProtectedNumField("基础绩效 (元/月)", basePerf)     { basePerf    = it; save() }
        ProtectedNumField("正常时薪 (元/时)", normalRate)   { normalRate  = it; save() }
        ProtectedNumField("加班时薪 (元/时)", otRate)       { otRate      = it; save() }
        ProtectedNumField("周末时薪 (元/时)", wkndRate)     { wkndRate    = it; save() }
        ProtectedNumField("节假日时薪 (元/时)", holRate)    { holRate     = it; save() }
        ProtectedNumField("社保扣款 (元/月)", insurance)    { insurance   = it; save() }
        ProtectedNumField("公积金扣款 (元/月)", housingFund){ housingFund = it; save() }

        // 底部间距，避免被导航栏遮挡
        Spacer(Modifier.height(16.dp))
    }
}
