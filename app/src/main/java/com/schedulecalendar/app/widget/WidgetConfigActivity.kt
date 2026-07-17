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
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
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
// 各小组件独立透明度键
const val KEY_CFG_CALENDAR_BG_TRANSPARENCY = "cfg_calendar_bg_transparency"
const val KEY_CFG_SCHEDULE_BG_TRANSPARENCY = "cfg_schedule_bg_transparency"

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
    // 读取各自独立的透明度
    val calendarTransparencyKey = if (isScheduleWidget) KEY_CFG_SCHEDULE_BG_TRANSPARENCY else KEY_CFG_CALENDAR_BG_TRANSPARENCY
    val savedTransparency = prefs.getFloat(calendarTransparencyKey, 0.0f)  // 默认0%=不透明
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
    val defaultBgColor = Color.White

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
                        // 同步写入（含默认颜色 + 各自透明度 + 显示模式）
                        val transparencyKey = if (isScheduleWidget) KEY_CFG_SCHEDULE_BG_TRANSPARENCY else KEY_CFG_CALENDAR_BG_TRANSPARENCY
                        prefs.edit()
                            .putString(KEY_CFG_TEXT_COLOR, "#FF333333")
                            .putString(KEY_CFG_BG_COLOR, "#FFFFFFFF")
                            .putFloat(transparencyKey, bgTransparency)
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
            val previewAlpha = 1.0f - bgTransparency  // 透明度滑块：0%=不透明，100%=全透明
            val previewBg = defaultBgColor.copy(alpha = previewAlpha)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(containerColor = previewBg)
            ) {
                PreviewContent(isScheduleWidget, defaultTextColor)
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
                            text = if (isScheduleWidget) "快捷打卡背景透明度" else "日历背景透明度",
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
                        // 渐变条：左=不透明，右=全透明
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(
                                brush = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent))
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

// ── 预览内容 ──
@Composable
private fun PreviewContent(isSchedule: Boolean, textColor: Color) {
    if (isSchedule) {
        // 快捷打卡预览
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 6.dp)
            ) {
                Text("白班", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF059669))
                Text("08:00 – 17:00", fontSize = 11.sp, color = textColor.copy(alpha = 0.6f))
                Text("明天：休息", fontSize = 9.sp, color = textColor.copy(alpha = 0.4f))
            }
            Box(
                modifier = Modifier
                    .width(44.dp).height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF059669).copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text("上班卡", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
            }
        }
    } else {
        // 日历预览
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("2026年7月", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = textColor)
                Text("↻", fontSize = 14.sp, color = textColor.copy(alpha = 0.5f))
            }
            Spacer(Modifier.height(3.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("一","二","三","四","五","六","日").forEach {
                    Text(it, fontSize = 7.sp, color = textColor.copy(alpha = 0.45f),
                        modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(1.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                repeat(3) { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        repeat(7) { col ->
                            val dayNum = row * 7 + col - 2
                            val isToday = dayNum == 18
                            Box(
                                modifier = Modifier.weight(1f).padding(0.5.dp).height(16.dp)
                                    .then(if (isToday) Modifier.background(
                                        Color(0xFF2E7D32).copy(alpha = 0.3f), RoundedCornerShape(3.dp)
                                    ) else Modifier),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                if (dayNum in 1..31) {
                                    Text("$dayNum", fontSize = 6.sp,
                                        color = if (isToday) Color(0xFF2E7D32) else textColor.copy(alpha = 0.65f),
                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
