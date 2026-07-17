// app/src/main/java/com/schedulecalendar/app/widget/WidgetConfigActivity.kt
package com.schedulecalendar.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.schedulecalendar.app.ui.theme.ScheduleCalendarTheme
import kotlinx.coroutines.runBlocking

// ── 配置持久化键 ──────────────────────────────────────────────────────
const val WIDGET_CONFIG_PREFS = "widget_config_prefs"
const val KEY_CFG_TEXT_COLOR      = "cfg_text_color"
const val KEY_CFG_BG_COLOR        = "cfg_bg_color"
const val KEY_CFG_BG_TRANSPARENCY = "cfg_bg_transparency"

// 2x1 快捷打卡小组件显示模式
const val KEY_CFG_DISPLAY_MODE = "cfg_schedule_display_mode"
const val DISPLAY_MODE_SHIFT_TOMORROW = "shift_tomorrow"    // 当天班次 + 明天班次
const val DISPLAY_MODE_SHIFT_HOLIDAY  = "shift_holiday"     // 当天班次 + 法定节假日倒计时

// ── 从设置页面打开时传递的小组件类型 ─────────────────────────────────
const val WIDGET_TYPE_CALENDAR = "calendar"
const val WIDGET_TYPE_SCHEDULE = "schedule"

// ── 配置 Activity ────────────────────────────────────────────────────

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val widgetType = intent?.getStringExtra("widget_type")
        val isFromSettings = widgetType != null

        val isScheduleWidget: Boolean
        val appWidgetId: Int

        if (isFromSettings) {
            appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            isScheduleWidget = widgetType == WIDGET_TYPE_SCHEDULE
        } else {
            appWidgetId = intent?.extras?.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

            val appWidgetManager = AppWidgetManager.getInstance(this)
            val info = appWidgetManager.getAppWidgetInfo(appWidgetId)
            isScheduleWidget = info?.provider?.className?.contains("ScheduleGlanceReceiver") == true
        }

        setContent {
            ScheduleCalendarTheme {
                WidgetConfigScreen(
                    appWidgetId = appWidgetId,
                    isScheduleWidget = isScheduleWidget,
                    onDone = { finishConfig(appWidgetId) }
                )
            }
        }
    }

    private fun finishConfig(appWidgetId: Int) {
        val appCtx = applicationContext
        // runBlocking 保证 finish() 前同步完成更新，防止国产 ROM 销毁 Activity 后 IO 协程被丢弃
        runBlocking {
            runCatching {
                CalendarGlanceWidget().let { w ->
                    GlanceAppWidgetManager(appCtx).getGlanceIds(w.javaClass).forEach { w.update(appCtx, it) }
                }
                ScheduleGlanceWidget().let { w ->
                    GlanceAppWidgetManager(appCtx).getGlanceIds(w.javaClass).forEach { w.update(appCtx, it) }
                }
            }
        }
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val resultIntent = Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            setResult(RESULT_OK, resultIntent)
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}

// ── 配置 UI ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigScreen(
    appWidgetId: Int,
    isScheduleWidget: Boolean,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(WIDGET_CONFIG_PREFS, Context.MODE_PRIVATE)
    val savedTransparency = prefs.getFloat(KEY_CFG_BG_TRANSPARENCY, 1.0f)
    var bgTransparency by remember { mutableFloatStateOf(savedTransparency) }

    val savedMode = prefs.getString(KEY_CFG_DISPLAY_MODE, DISPLAY_MODE_SHIFT_TOMORROW)
        ?: DISPLAY_MODE_SHIFT_TOMORROW
    var selectedMode by remember { mutableStateOf(savedMode) }

    val displayModes = listOf(
        DISPLAY_MODE_SHIFT_TOMORROW to "当天班次 + 明天班次",
        DISPLAY_MODE_SHIFT_HOLIDAY  to "当天班次 + 法定节假日倒计时"
    )

    // 默认颜色（不再支持配置，写死为默认值）
    val defaultTextColor = Color(0xFF333333)
    val defaultBgColor = Color(0xFFF5F5F5)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("小组件样式配置", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Button(
                    onClick = {
                        // 同步写入（含默认颜色 + 透明度 + 显示模式）
                        prefs.edit()
                            .putString(KEY_CFG_TEXT_COLOR, "#FF333333")
                            .putString(KEY_CFG_BG_COLOR, "#FFF5F5F5")
                            .putFloat(KEY_CFG_BG_TRANSPARENCY, bgTransparency)
                            .putString(KEY_CFG_DISPLAY_MODE, selectedMode)
                            .commit()
                        onDone()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("保存配置", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── 预览卡片 ──
            val previewBg = defaultBgColor.copy(alpha = bgTransparency)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = previewBg),
                border = BorderStroke(0.dp, Color.Transparent)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "预览效果",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = defaultTextColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "7月17日 白班",
                        fontSize = 11.sp,
                        color = defaultTextColor.copy(alpha = 0.7f)
                    )
                }
            }

            // ── 背景透明度 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ),
                border = BorderStroke(0.dp, Color.Transparent)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "背景透明度",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${(bgTransparency * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(
                                brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black))
                            )
                        }
                        Slider(
                            value = bgTransparency,
                            onValueChange = { bgTransparency = (it * 100).toInt() / 100f },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxSize(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.Gray.copy(alpha = bgTransparency),
                                activeTrackColor = Color.Transparent,
                                inactiveTrackColor = Color.Transparent,
                            )
                        )
                    }
                }
            }

            // ── 显示模式（仅2x1小组件） ──
            if (isScheduleWidget) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    border = BorderStroke(0.dp, Color.Transparent)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "快捷打卡显示模式",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            displayModes.forEach { (mode, label) ->
                                val isModeSelected = selectedMode == mode
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedMode = mode },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isModeSelected)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(
                                            horizontal = 10.dp, vertical = 8.dp
                                        ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isModeSelected,
                                            onClick = { selectedMode = mode },
                                            colors = RadioButtonDefaults.colors(
                                                selectedColor = MaterialTheme.colorScheme.primary
                                            )
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isModeSelected)
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            else
                                                MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 底部间距
            Spacer(Modifier.height(8.dp))
        }
    }
}


