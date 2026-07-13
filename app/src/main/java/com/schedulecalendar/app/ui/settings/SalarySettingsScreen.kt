// app/src/main/java/com/schedulecalendar/app/ui/settings/SalarySettingsScreen.kt
package com.schedulecalendar.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var baseSalary   by remember(config) { mutableStateOf(config.baseSalary.toInputString()) }
    var basePerf     by remember(config) { mutableStateOf(config.basePerformance.toInputString()) }
    var normalRate   by remember(config) { mutableStateOf(config.normalRate.toInputString()) }
    var otRate       by remember(config) { mutableStateOf(config.overtimeRate.toInputString()) }
    var wkndRate     by remember(config) { mutableStateOf(config.weekendRate.toInputString()) }
    var holRate      by remember(config) { mutableStateOf(config.holidayRate.toInputString()) }
    var insurance    by remember(config) { mutableStateOf(config.socialInsurance.toInputString()) }
    var housingFund  by remember(config) { mutableStateOf(config.housingFundDeduction.toInputString()) }

    fun save() = onSave(SalaryConfig(
        baseSalary          = baseSalary.toDoubleOrNull()   ?: 0.0,
        basePerformance     = basePerf.toDoubleOrNull()     ?: 0.0,
        normalRate          = normalRate.toDoubleOrNull()   ?: 0.0,
        overtimeRate        = otRate.toDoubleOrNull()       ?: 0.0,
        weekendRate         = wkndRate.toDoubleOrNull()     ?: 0.0,
        holidayRate         = holRate.toDoubleOrNull()      ?: 0.0,
        socialInsurance     = insurance.toDoubleOrNull()    ?: 0.0,
        housingFundDeduction = housingFund.toDoubleOrNull() ?: 0.0
    ))

    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProtectedNumField("\u57fa\u7840\u5e95\u85aa (\u5143/\u6708)", baseSalary)   { baseSalary  = it; save() }
        ProtectedNumField("\u57fa\u7840\u7ee9\u6548 (\u5143/\u6708)", basePerf)     { basePerf    = it; save() }
        ProtectedNumField("\u6b63\u5e38\u65f6\u85aa (\u5143/\u65f6)", normalRate)   { normalRate  = it; save() }
        ProtectedNumField("\u52a0\u73ed\u65f6\u85aa (\u5143/\u65f6)", otRate)       { otRate      = it; save() }
        ProtectedNumField("\u5468\u672b\u65f6\u85aa (\u5143/\u65f6)", wkndRate)     { wkndRate    = it; save() }
        ProtectedNumField("\u8282\u5047\u65e5\u65f6\u85aa (\u5143/\u65f6)", holRate)     { holRate     = it; save() }
        ProtectedNumField("\u793e\u4fdd\u6263\u6b3e (\u5143/\u6708)", insurance)   { insurance   = it; save() }
        ProtectedNumField("\u516c\u79ef\u91d1\u6263\u6b3e (\u5143/\u6708)", housingFund)  { housingFund = it; save() }
    }
}
