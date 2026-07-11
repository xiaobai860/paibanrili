package com.schedulecalendar.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.schedulecalendar.app.ui.component.ScheduleTopBar

@Composable
fun OtherSettingsScreen(navController: NavController) {
    Scaffold(
        topBar = { ScheduleTopBar("其它设置", onBack = { navController.popBackStack() }) }
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text("功能开发中", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
