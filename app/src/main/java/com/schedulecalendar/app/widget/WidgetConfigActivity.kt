// app/src/main/java/com/schedulecalendar/app/widget/WidgetConfigActivity.kt
package com.schedulecalendar.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

// ── HSV / Hex 颜色转换工具 ───────────────────────────────────────────

/** HSV → "#AARRGGBB"（alpha 固定 FF） */
private fun hsvToHex(hue: Float, sat: Float, value: Float): String {
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(
        hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f)
    ))
    return "#${argb.toLong().toString(16).padStart(8, '0').uppercase()}"
}

/** "#AARRGGBB" → HSV 浮点数组 */
private fun hexToHsv(hex: String): FloatArray {
    val h = hex.removePrefix("#")
    val r = h.substring(h.length - 6, h.length - 4).toIntOrNull(16) ?: 0x33
    val g = h.substring(h.length - 4, h.length - 2).toIntOrNull(16) ?: 0x33
    val b = h.substring(h.length - 2).toIntOrNull(16) ?: 0x33
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(r, g, b, hsv)
    return hsv
}

/** ARGB int → Compose Color */
private fun argbToColor(argb: Int): Color {
    val a = ((argb shr 24) and 0xFF) / 255f
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return Color(r, g, b, a)
}

/** HSV → Compose Color */
private fun hsvToColor(hue: Float, sat: Float, value: Float): Color =
    argbToColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))

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
        // 始终更新所有小组件
        val ctx = this
        CoroutineScope(Dispatchers.Main).launch {
            GlanceAppWidgetManager(ctx).getGlanceIds(CalendarGlanceWidget::class.java).forEach { id ->
                CalendarGlanceWidget().update(ctx, id)
            }
            GlanceAppWidgetManager(ctx).getGlanceIds(ScheduleGlanceWidget::class.java).forEach { id ->
                ScheduleGlanceWidget().update(ctx, id)
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
    val savedTextColor = prefs.getString(KEY_CFG_TEXT_COLOR, "#FF333333") ?: "#FF333333"
    val savedBgColor = prefs.getString(KEY_CFG_BG_COLOR, "#FFF5F5F5") ?: "#FFF5F5F5"
    val savedTransparency = prefs.getFloat(KEY_CFG_BG_TRANSPARENCY, 1.0f)

    // 文字颜色 HSV state
    val textHsv = remember(savedTextColor) { hexToHsv(savedTextColor) }
    var textHue by remember { mutableFloatStateOf(textHsv[0]) }
    var textSat by remember { mutableFloatStateOf(textHsv[1]) }
    var textVal by remember { mutableFloatStateOf(textHsv[2]) }

    // 背景颜色 HSV state
    val bgHsv = remember(savedBgColor) { hexToHsv(savedBgColor) }
    var bgHue by remember { mutableFloatStateOf(bgHsv[0]) }
    var bgSat by remember { mutableFloatStateOf(bgHsv[1]) }
    var bgVal by remember { mutableFloatStateOf(bgHsv[2]) }

    var bgTransparency by remember { mutableFloatStateOf(savedTransparency) }

    val textColor = remember(textHue, textSat, textVal) { hsvToColor(textHue, textSat, textVal) }
    val textColorHex = remember(textHue, textSat, textVal) { hsvToHex(textHue, textSat, textVal) }

    val bgColor = remember(bgHue, bgSat, bgVal) { hsvToColor(bgHue, bgSat, bgVal) }
    val bgColorHex = remember(bgHue, bgSat, bgVal) { hsvToHex(bgHue, bgSat, bgVal) }

    val savedMode = prefs.getString(KEY_CFG_DISPLAY_MODE, DISPLAY_MODE_SHIFT_TOMORROW)
        ?: DISPLAY_MODE_SHIFT_TOMORROW
    var selectedMode by remember { mutableStateOf(savedMode) }

    val displayModes = listOf(
        DISPLAY_MODE_SHIFT_TOMORROW to "当天班次 + 明天班次",
        DISPLAY_MODE_SHIFT_HOLIDAY  to "当天班次 + 法定节假日倒计时"
    )

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
                        prefs.edit()
                            .putString(KEY_CFG_TEXT_COLOR, textColorHex)
                            .putString(KEY_CFG_BG_COLOR, bgColorHex)
                            .putFloat(KEY_CFG_BG_TRANSPARENCY, bgTransparency)
                            .putString(KEY_CFG_DISPLAY_MODE, selectedMode)
                            .apply()
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
            val previewBg = bgColor.copy(alpha = bgTransparency)
            val previewText = textColor
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(containerColor = previewBg)
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
                        color = previewText
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "7月17日 白班",
                        fontSize = 11.sp,
                        color = previewText.copy(alpha = 0.7f)
                    )
                }
            }

            // ── 字体颜色 ──
            ColorSliderSection(
                label = "字体颜色",
                hexColor = textColorHex,
                color = textColor,
                hue = textHue, saturation = textSat, value = textVal,
                onHueChanged = { textHue = (it * 10).toInt() / 10f },
                onSatChanged = { textSat = (it * 20).toInt() / 20f },
                onValChanged = { textVal = (it * 20).toInt() / 20f }
            )

            // ── 背景颜色 ──
            ColorSliderSection(
                label = "背景颜色",
                hexColor = bgColorHex,
                color = bgColor,
                hue = bgHue, saturation = bgSat, value = bgVal,
                onHueChanged = { bgHue = (it * 10).toInt() / 10f },
                onSatChanged = { bgSat = (it * 20).toInt() / 20f },
                onValChanged = { bgVal = (it * 20).toInt() / 20f }
            )

            // ── 背景透明度 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
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
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
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

// ── 颜色滑块选择区段（色相 + 饱和度 + 亮度） ─────────────────────────

@Composable
private fun ColorSliderSection(
    label: String,
    hexColor: String,
    color: Color,
    hue: Float,
    saturation: Float,
    value: Float,
    onHueChanged: (Float) -> Unit,
    onSatChanged: (Float) -> Unit,
    onValChanged: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(color)
                            .border(
                                0.5.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(4.dp)
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = hexColor.lowercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 色相
            GradientSlider(
                label = "色相",
                value = hue,
                onValueChange = onHueChanged,
                valueRange = 0f..360f,
                steps = 35,
                gradientColors = listOf(
                    Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                    Color.Blue, Color.Magenta, Color.Red
                ),
                thumbColor = hsvToColor(hue, saturation, value)
            )

            Spacer(Modifier.height(6.dp))

            // 饱和度
            GradientSlider(
                label = "饱和度",
                value = saturation,
                onValueChange = onSatChanged,
                valueRange = 0f..1f,
                steps = 19,
                gradientColors = null,
                gradientStart = hsvToColor(hue, 0f, value),
                gradientEnd = hsvToColor(hue, 1f, value),
                thumbColor = hsvToColor(hue, saturation, value)
            )

            Spacer(Modifier.height(6.dp))

            // 亮度
            GradientSlider(
                label = "亮度",
                value = value,
                onValueChange = onValChanged,
                valueRange = 0f..1f,
                steps = 19,
                gradientColors = null,
                gradientStart = hsvToColor(hue, saturation, 0f),
                gradientEnd = hsvToColor(hue, saturation, 1f),
                thumbColor = hsvToColor(hue, saturation, value)
            )
        }
    }
}

// ── 渐变背景滑块 ──────────────────────────────────────────────────────

@Composable
private fun GradientSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    gradientColors: List<Color>?,
    gradientStart: Color = Color.Transparent,
    gradientEnd: Color = Color.Transparent,
    thumbColor: Color
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val display = if (valueRange.endInclusive > 2f) {
                "${value.toInt()}°"
            } else {
                "${(value * 100).toInt()}%"
            }
            Text(
                text = display,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (gradientColors != null) {
                    drawRect(brush = Brush.horizontalGradient(gradientColors))
                } else {
                    drawRect(
                        brush = Brush.horizontalGradient(listOf(gradientStart, gradientEnd))
                    )
                }
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.fillMaxSize(),
                colors = SliderDefaults.colors(
                    thumbColor = thumbColor,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                )
            )
        }
    }
}
