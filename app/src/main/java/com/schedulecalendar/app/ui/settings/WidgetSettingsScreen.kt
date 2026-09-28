package com.schedulecalendar.app.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import com.schedulecalendar.app.widget.CalendarGlance3x4Receiver
import com.schedulecalendar.app.widget.CalendarGlanceReceiver
import com.schedulecalendar.app.widget.ScheduleGlanceReceiver
import com.schedulecalendar.app.widget.WIDGET_TYPE_CALENDAR
import com.schedulecalendar.app.widget.WIDGET_TYPE_SCHEDULE
import com.schedulecalendar.app.widget.WidgetConfigActivity
import com.schedulecalendar.app.widget.WidgetPinHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSettingsScreen(
    navController: NavController,
    vm: WidgetSettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { ScheduleTopBar("小部件设置", onBack = { navController.popBackStack() }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── 使用引导 ────────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "点击下方每张卡片的「添加到桌面」即可一键钉到桌面；也可长按桌面空白处 → 添加小组件 → 选择「排班日历」或「快捷打卡」，再回来配置样式",
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── 日历小组件配置（3×3） ──────────────────────────────────
            WidgetConfigCard(
                icon = Icons.Default.CalendarMonth,
                iconBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                iconTint = MaterialTheme.colorScheme.primary,
                title = "日历小组件配置",
                description = "3×3 网格式日历，展示当月排班与工时",
                badge = "3×3",
                onClick = {
                    val intent = Intent(context, WidgetConfigActivity::class.java).apply {
                        putExtra("widget_type", WIDGET_TYPE_CALENDAR)
                    }
                    context.startActivity(intent)
                },
                onAddToHome = { WidgetPinHelper.pin(context, CalendarGlanceReceiver::class.java) }
            )

            // ── 日历小组件配置（3×4） ──────────────────────────────────
            WidgetConfigCard(
                icon = Icons.Default.CalendarMonth,
                iconBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                iconTint = MaterialTheme.colorScheme.primary,
                title = "日历小组件配置（大）",
                description = "3×4 网格式日历，展示当月排班、工时与附加状态",
                badge = "3×4",
                onClick = {
                    val intent = Intent(context, WidgetConfigActivity::class.java).apply {
                        putExtra("widget_type", WIDGET_TYPE_CALENDAR)
                    }
                    context.startActivity(intent)
                },
                onAddToHome = { WidgetPinHelper.pin(context, CalendarGlance3x4Receiver::class.java) }
            )

            // ── 快捷打卡配置 ──────────────────────────────────────────
            WidgetConfigCard(
                icon = Icons.Default.Fingerprint,
                iconBg = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                iconTint = MaterialTheme.colorScheme.tertiary,
                title = "快捷打卡配置",
                description = "2×1 快捷打卡按钮，一键上下班打卡",
                badge = "2×1",
                onClick = {
                    val intent = Intent(context, WidgetConfigActivity::class.java).apply {
                        putExtra("widget_type", WIDGET_TYPE_SCHEDULE)
                    }
                    context.startActivity(intent)
                },
                onAddToHome = { WidgetPinHelper.pin(context, ScheduleGlanceReceiver::class.java) }
            )

            Spacer(Modifier.weight(1f))
            Text(
                "配置完成后返回桌面即可看到效果",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WidgetConfigCard(
    icon: ImageVector,
    iconBg: androidx.compose.ui.graphics.Color,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    description: String,
    badge: String,
    onClick: () -> Unit,
    onAddToHome: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 图标容器（圆形浅色底）
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(iconBg, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(8.dp))
                        // 尺寸徽章
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(14.dp))
            // 一键添加到桌面
            OutlinedButton(
                onClick = onAddToHome,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("添加到桌面", fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ── ViewModel ──────────────────────────────────────────────────────────

@HiltViewModel
class WidgetSettingsViewModel @Inject constructor(
    private val prefs: AppPreferences
) : ViewModel()
