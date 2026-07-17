// app/src/main/java/com/schedulecalendar/app/widget/WidgetConfigActivity.kt
package com.schedulecalendar.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

const val WIDGET_CONFIG_PREFS = "widget_config_prefs"
const val KEY_CFG_TEXT_COLOR      = "cfg_text_color"
const val KEY_CFG_BG_COLOR        = "cfg_bg_color"
const val KEY_CFG_BG_TRANSPARENCY = "cfg_bg_transparency"

// 2x1 快捷打卡小组件显示模式
const val KEY_CFG_DISPLAY_MODE = "cfg_schedule_display_mode"
const val DISPLAY_MODE_SHIFT_TOMORROW = "shift_tomorrow"    // 当天班次 + 明天班次
const val DISPLAY_MODE_SHIFT_HOLIDAY  = "shift_holiday"     // 当天班次 + 法定节假日倒计时

data class ColorOption(val label: String, val hex: String)

val textColorOptions = listOf(
    ColorOption("\u6df1\u9ed1", "#FF333333"),
    ColorOption("\u7070\u8272", "#FF757575"),
    ColorOption("\u767d\u8272", "#FFFFFFFF"),
    ColorOption("\u84dd\u8272", "#FF1565C0"),
    ColorOption("\u7eff\u8272", "#FF2E7D32"),
)

val bgColorOptions = listOf(
    ColorOption("\u767d\u8272", "#FFFFFFFF"),
    ColorOption("\u6d45\u7070", "#FFF5F5F5"),
    ColorOption("\u6de1\u84dd", "#FFE3F2FD"),
    ColorOption("\u6de1\u7eff", "#FFE8F5E9"),
    ColorOption("\u6de1\u6a59", "#FFFFF3E0"),
    ColorOption("\u6df1\u8272", "#FF263238"),
    ColorOption("\u9ed1\u8272", "#FF1A1A1A"),
)

private fun hexToColor(hex: String, fallback: Color = Color.Black): Color {
    return runCatching {
        val h = hex.removePrefix("#")
        val a = if (h.length == 8) h.substring(0, 2).toInt(16) / 255f else 1f
        val r = h.substring(h.length - 6, h.length - 4).toInt(16) / 255f
        val g = h.substring(h.length - 4, h.length - 2).toInt(16) / 255f
        val b = h.substring(h.length - 2).toInt(16) / 255f
        Color(r, g, b, a)
    }.getOrElse { fallback }
}

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setContent {
            ScheduleCalendarTheme {
                WidgetConfigScreen(appWidgetId = appWidgetId, onDone = { finishConfig(appWidgetId) })
            }
        }
    }

    private fun finishConfig(appWidgetId: Int) {
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val ctx = this
            CoroutineScope(Dispatchers.Main).launch {
                // 刷新日历小组件
                GlanceAppWidgetManager(ctx).getGlanceIds(CalendarGlanceWidget::class.java).forEach { id ->
                    CalendarGlanceWidget().update(ctx, id)
                }
                // 刷新快捷打卡小组件
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

@Composable
private fun WidgetConfigScreen(appWidgetId: Int, onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(WIDGET_CONFIG_PREFS, Context.MODE_PRIVATE)
    val savedTextColor = prefs.getString(KEY_CFG_TEXT_COLOR, "#FF333333") ?: "#FF333333"
    val savedBgColor = prefs.getString(KEY_CFG_BG_COLOR, "#FFF5F5F5") ?: "#FFF5F5F5"
    val savedTransparency = prefs.getFloat(KEY_CFG_BG_TRANSPARENCY, 1.0f)
    var selectedTextColor by remember { mutableStateOf(savedTextColor) }
    var selectedBgColor by remember { mutableStateOf(savedBgColor) }
    var bgTransparency by remember { mutableStateOf(savedTransparency) }

    // 2x1 显示模式
    val savedMode = prefs.getString(KEY_CFG_DISPLAY_MODE, DISPLAY_MODE_SHIFT_TOMORROW) ?: DISPLAY_MODE_SHIFT_TOMORROW
    var selectedMode by remember { mutableStateOf(savedMode) }

    val displayModes = listOf(
        DISPLAY_MODE_SHIFT_TOMORROW to "\u5f53\u5929\u73ed\u6b21 + \u660e\u5929\u73ed\u6b21",
        DISPLAY_MODE_SHIFT_HOLIDAY  to "\u5f53\u5929\u73ed\u6b21 + \u6cd5\u5b9a\u5047\u65e5\u5012\u8ba1\u65f6"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "\u5c0f\u7ec4\u4ef6\u6837\u5f0f\u914d\u7f6e",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A1A1A),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // ── 预览 ──
        val previewBgColor = hexToColor(selectedBgColor, Color(0xFFF5F5F5))
        val previewTextColor = hexToColor(selectedTextColor, Color(0xFF333333))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(previewBgColor.copy(alpha = bgTransparency))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "\u9884\u89c8\u6548\u679c",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = previewTextColor
                )
                Text(
                    text = "7\u670817\u65e5 \u767d\u73ed",
                    fontSize = 11.sp,
                    color = previewTextColor.copy(alpha = 0.7f)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // ── 字体颜色选择 ──
        Text(
            text = "\u5b57\u4f53\u989c\u8272",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF424242),
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            textColorOptions.forEach { opt ->
                val color = hexToColor(opt.hex, Color.Black)
                val isSelected = opt.hex == selectedTextColor
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp).height(40.dp)
                            .background(if (isSelected) Color(0xFF2196F3) else Color(0xFFBDBDBD))
                            .padding(2.dp)
                            .clickable { selectedTextColor = opt.hex },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(34.dp).height(34.dp)
                                .background(color),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) Text(
                                text = "\u2713",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = opt.label,
                        color = Color(0xFF757575),
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        // ── 背景颜色选择 ──
        Text(
            text = "\u80cc\u666f\u989c\u8272",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF424242),
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            bgColorOptions.forEach { opt ->
                val color = hexToColor(opt.hex, Color.White)
                val isSelected = opt.hex == selectedBgColor
                val isDark = opt.hex == "#FF263238" || opt.hex == "#FF1A1A1A"
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp).height(36.dp)
                            .background(if (isSelected) Color(0xFF2196F3) else Color(0xFFBDBDBD))
                            .padding(2.dp)
                            .clickable { selectedBgColor = opt.hex },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(30.dp).height(30.dp)
                                .background(color),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) Text(
                                text = "\u2713",
                                color = if (isDark) Color.White else Color.Black,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = opt.label,
                        color = Color(0xFF757575),
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        // ── 背景透明度 ──
        Text(
            text = "\u80cc\u666f\u900f\u660e\u5ea6\uff1a${(bgTransparency * 100).toInt()}%",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF424242),
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
        )
        val transparencyOptions = listOf(0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f)
        Row(modifier = Modifier.fillMaxWidth()) {
            transparencyOptions.forEach { level ->
                val isThisLevel = bgTransparency == level
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                        .height(32.dp)
                        .background(if (isThisLevel) Color(0xFF2196F3) else Color(0xFFE0E0E0))
                        .clickable { bgTransparency = level },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${(level * 100).toInt()}",
                        color = if (isThisLevel) Color.White else Color(0xFF616161),
                        fontSize = 10.sp,
                        fontWeight = if (isThisLevel) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // ── 2x1 快捷打卡显示模式选择 ──
        Text(
            text = "\u5feb\u6377\u6253\u5361\u663e\u793a\u6a21\u5f0f",
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
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isModeSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.White, shape = androidx.compose.foundation.shape.CircleShape)
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

        // ── 保存按钮 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Color(0xFF2196F3))
                .clickable {
                    prefs.edit()
                        .putString(KEY_CFG_TEXT_COLOR, selectedTextColor)
                        .putString(KEY_CFG_BG_COLOR, selectedBgColor)
                        .putFloat(KEY_CFG_BG_TRANSPARENCY, bgTransparency)
                        .putString(KEY_CFG_DISPLAY_MODE, selectedMode)
                        .apply()
                    onDone()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "\u4fdd\u5b58\u914d\u7f6e",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
