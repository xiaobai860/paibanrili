package com.schedulecalendar.app.ui.settings

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import com.schedulecalendar.app.widget.WIDGET_TYPE_CALENDAR
import com.schedulecalendar.app.widget.WIDGET_TYPE_SCHEDULE
import com.schedulecalendar.app.widget.WidgetConfigActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSettingsScreen(
    navController: NavController,
    vm: WidgetSettingsViewModel = hiltViewModel()
) {
    val uiState by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    
    Scaffold(
        topBar = { ScheduleTopBar("小部件设置", onBack = { navController.popBackStack() }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── 日历小组件配置 ──
            SettingsCard(
                icon = Icons.Default.CalendarMonth,
                title = "日历小组件配置",
                description = "3×3 日历网格式样配置",
                onClick = {
                    val intent = Intent(context, WidgetConfigActivity::class.java).apply {
                        putExtra("widget_type", WIDGET_TYPE_CALENDAR)
                    }
                    context.startActivity(intent)
                }
            )

            // ── 快捷打卡配置 ──
            SettingsCard(
                icon = Icons.Default.Fingerprint,
                title = "快捷打卡配置",
                description = "2×1 快捷打卡样式配置",
                onClick = {
                    val intent = Intent(context, WidgetConfigActivity::class.java).apply {
                        putExtra("widget_type", WIDGET_TYPE_SCHEDULE)
                    }
                    context.startActivity(intent)
                }
            )
        }
    }
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── ViewModel ──────────────────────────────────────────────────────────

data class WidgetSettingsUiState(
)

@HiltViewModel
class WidgetSettingsViewModel @Inject constructor(
    private val prefs: AppPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(WidgetSettingsUiState())
    val state = _state.asStateFlow()
}
