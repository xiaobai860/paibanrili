// app/src/main/java/com/schedulecalendar/app/widget/WidgetConfigActivity.kt
package com.schedulecalendar.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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

// ── HSV / Hex 颜色转换工具 ───────────────────────────────────────────

/** HSV → "#AARRGGBB"（alpha 固定 FF） */
private fun hsvToHex(hue: Float, sat: Float, value: Float): String {
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f)))
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

/** "#AARRGGBB" → Compose Color */
private fun hexToComposeColor(hex: String, fallback: Color = Color.Black): Color {
    return runCatching {
        val h = hex.removePrefix("#")
        val a = if (h.length == 8) h.substring(0, 2).toInt(16) / 255f else 1f
        val r = h.substring(h.length - 6, h.length - 4).toInt(16) / 255f
        val g = h.substring(h.length - 4, h.length - 2).toInt(16) / 255f
        val b = h.substring(h.length - 2).toInt(16) / 255f
        Color(r, g, b, a)
    }.getOrElse { fallback }
}

// ── 配置 Activity ────────────────────────────────────────────────────

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // 判断被配置的小组件类型
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val info = appWidgetManager.getAppWidgetInfo(appWidgetId)
        val isScheduleWidget = info?.provider?.className?.contains("ScheduleGlanceReceiver") == true

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
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val ctx = this
            CoroutineScope(Dispatchers.Main).launch {
                GlanceAppWidgetManager(ctx).getGlanceIds(CalendarGlanceWidget::class.java).forEach { id ->
                    CalendarGlanceWidget().update(ctx, id)
                }
                GlanceAppWidgetManager(ctx).getGlanceIds(ScheduleGlanceWidget::class.java).forEach { id ->
                    ScheduleGlanceWidget().update(ctx, id)
                }
            }
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

    // 文字颜色计算
    val textColor = remember(textHue, textSat, textVal) {
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(textHue, textSat, textVal))
        Color(argb)
    }
    val textColorHex = remember(textHue, textSat, textVal) { hsvToHex(textHue, textSat, textVal) }

    // 背景颜色计算
    val bgColor = remember(bgHue, bgSat, bgVal) {
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(bgHue, bgSat, bgVal))
        Color(argb)
    }
    val bgColorHex = remember(bgHue, bgSat, bgVal) { hsvToHex(bgHue, bgSat, bgVal) }

    // 2x1 显示模式
    val savedMode = prefs.getString(KEY_CFG_DISPLAY_MODE, DISPLAY_MODE_SHIFT_TOMORROW) ?: DISPLAY_MODE_SHIFT_TOMORROW
    var selectedMode by remember { mutableStateOf(savedMode) }

    val displayModes = listOf(
        DISPLAY_MODE_SHIFT_TOMORROW to "当天班次 + 明天班次",
        DISPLAY_MODE_SHIFT_HOLIDAY  to "当天班次 + 法定节假日倒计时"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── 标题 ──
        Text(
            text = "小组件样式配置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A1A1A),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // ── 预览 ──
        val previewBg = bgColor.copy(alpha = bgTransparency)
        val previewText = textColor
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(previewBg)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "\u9884\u89c8\u6548\u679c",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = previewText
                )
                Text(
                    text = "7\u670817\u65e5 \u767d\u73ed",
                    fontSize = 11.sp,
                    color = previewText.copy(alpha = 0.7f)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // ── 字体颜色选色器 ──
        ColorPickerSection(
            label = "字体颜色",
            initialHex = savedTextColor,
            onColorChanged = { hex ->
                val hsv = hexToHsv(hex)
                textHue = hsv[0]; textSat = hsv[1]; textVal = hsv[2]
            }
        )
        Spacer(modifier = Modifier.height(14.dp))

        // ── 背景颜色选色器 ──
        ColorPickerSection(
            label = "背景颜色",
            initialHex = savedBgColor,
            onColorChanged = { hex ->
                val hsv = hexToHsv(hex)
                bgHue = hsv[0]; bgSat = hsv[1]; bgVal = hsv[2]
            }
        )
        Spacer(modifier = Modifier.height(14.dp))

        // ── 背景透明度 ──
        Text(
            text = "背景透明度：${(bgTransparency * 100).toInt()}%",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF424242),
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
        )
        val transColor = remember(bgTransparency) {
            Color.LightGray.copy(alpha = bgTransparency)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black))
                )
        ) {
            Slider(
                value = bgTransparency,
                onValueChange = { bgTransparency = (it * 100).toInt() / 100f }, // 四舍五入到1%
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxSize(),
                colors = SliderDefaults.colors(
                    thumbColor = transColor,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                )
            )
        }
        Spacer(modifier = Modifier.height(20.dp))

        // ── 2x1 快捷打卡显示模式（仅2x1小组件显示） ──
        if (isScheduleWidget) {
            Text(
                text = "快捷打卡显示模式",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF424242),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            )
            displayModes.forEach { (mode, label) ->
                val isModeSelected = selectedMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(if (isModeSelected) Color(0xFFE3F2FD) else Color.Transparent)
                        .clickable { selectedMode = mode }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(20.dp).height(20.dp)
                            .background(
                                if (isModeSelected) Color(0xFF1976D2) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isModeSelected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.White, shape = RoundedCornerShape(10.dp))
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        color = if (isModeSelected) Color(0xFF1976D2) else Color(0xFF424242),
                        fontWeight = if (isModeSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // ── 保存按钮 ──
        val isLarge = true // 默认使用大字模式，预览用
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Color(0xFF2196F3))
                .clickable {
                    prefs.edit()
                        .putString(KEY_CFG_TEXT_COLOR, textColorHex)
                        .putString(KEY_CFG_BG_COLOR, bgColorHex)
                        .putFloat(KEY_CFG_BG_TRANSPARENCY, bgTransparency)
                        .putString(KEY_CFG_DISPLAY_MODE, selectedMode)
                        .apply()
                    onDone()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "保存配置",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ── 颜色选色器组件 ────────────────────────────────────────────────────

@Composable
private fun ColorPickerSection(
    label: String,
    initialHex: String,
    onColorChanged: (String) -> Unit
) {
    val hsv = remember(initialHex) { hexToHsv(initialHex) }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }

    // 预览色
    val currentColor = remember(hue, sat, value) {
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
        Color(argb)
    }

    val currentHex = remember(hue, sat, value) { hsvToHex(hue, sat, value) }

    Text(
        text = label,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = Color(0xFF424242),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
    )

    // 色相滑动条（彩虹渐变）
    Text(
        text = "色相：${hue.toInt()}°",
        fontSize = 11.sp,
        color = Color(0xFF757575),
        modifier = Modifier.padding(bottom = 2.dp)
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(4.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sectionWidth = size.width / 6f
            val rainbowColors = listOf(
                Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                Color.Blue, Color.Magenta, Color.Red
            )
            drawRect(brush = Brush.horizontalGradient(rainbowColors))
        }
        Slider(
            value = hue,
            onValueChange = {
                hue = it
                onColorChanged(hsvToHex(it, sat, value))
            },
            valueRange = 0f..360f,
            steps = 35, // 每10°一个步进
            modifier = Modifier.fillMaxSize(),
            colors = SliderDefaults.colors(
                thumbColor = currentColor,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            )
        )
    }
    Spacer(modifier = Modifier.height(8.dp))

    // 饱和度滑动条（黑白→纯色渐变）
    Text(
        text = "饱和度：${(sat * 100).toInt()}%",
        fontSize = 11.sp,
        color = Color(0xFF757575),
        modifier = Modifier.padding(bottom = 2.dp)
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(4.dp))
    ) {
        val satStart = remember(hue, value) {
            val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0f, value))
            Color(argb)
        }
        val satEnd = remember(hue, value) {
            val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, value))
            Color(argb)
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(brush = Brush.horizontalGradient(listOf(satStart, satEnd)))
        }
        Slider(
            value = sat,
            onValueChange = {
                sat = (it * 20).toInt() / 20f // 5%精度
                onColorChanged(hsvToHex(hue, sat, value))
            },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxSize(),
            colors = SliderDefaults.colors(
                thumbColor = currentColor,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            )
        )
    }
    Spacer(modifier = Modifier.height(8.dp))

    // 亮度滑动条（黑色→纯色渐变）
    Text(
        text = "亮度：${(value * 100).toInt()}%",
        fontSize = 11.sp,
        color = Color(0xFF757575),
        modifier = Modifier.padding(bottom = 2.dp)
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(4.dp))
    ) {
        val valStart = remember(hue, sat) {
            val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 0f))
            Color(argb)
        }
        val valEnd = remember(hue, sat) {
            val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 1f))
            Color(argb)
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(brush = Brush.horizontalGradient(listOf(valStart, valEnd)))
        }
        Slider(
            value = value,
            onValueChange = {
                value = (it * 20).toInt() / 20f // 5%精度
                onColorChanged(hsvToHex(hue, sat, value))
            },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxSize(),
            colors = SliderDefaults.colors(
                thumbColor = currentColor,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            )
        )
    }
    Spacer(modifier = Modifier.height(4.dp))

    // 当前颜色预览小方块
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(24.dp).height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(currentColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = currentHex.lowercase(),
            fontSize = 11.sp,
            color = Color(0xFF9E9E9E)
        )
    }
}
