// app/src/main/java/com/schedulecalendar/app/widget/WidgetConfigActivity.kt
package com.schedulecalendar.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.schedulecalendar.app.ui.theme.ScheduleCalendarTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val WIDGET_CONFIG_PREFS = "widget_config_prefs"
const val KEY_CFG_TEXT_COLOR      = "cfg_text_color"
const val KEY_CFG_BG_COLOR        = "cfg_bg_color"
const val KEY_CFG_BG_TRANSPARENCY = "cfg_bg_transparency"

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

private fun cp(color: Color): ColorProvider = object : ColorProvider {
    override fun getColor(context: Context): Color = color
}

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
                val gm = GlanceAppWidgetManager(ctx)
                gm.getGlanceIds(CalendarGlanceWidget::class.java).forEach { id ->
                    CalendarGlanceWidget().update(ctx, id)
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

    Column(
        modifier = GlanceModifier.fillMaxSize().background(cp(Color(0xFFF5F5F5))).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "\u5c0f\u7ec4\u4ef6\u6837\u5f0f\u914d\u7f6e",
            style = TextStyle(color = cp(Color(0xFF1A1A1A)), fontSize = 20.sp, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.padding(bottom = 16.dp)
        )
        val previewBgColor = hexToColor(selectedBgColor, Color(0xFFF5F5F5))
        val previewTextColor = hexToColor(selectedTextColor, Color(0xFF333333))
        Box(
            modifier = GlanceModifier.fillMaxWidth().height(80.dp)
                .background(cp(previewBgColor.copy(alpha = bgTransparency))).padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "\u9884\u89c8\u6548\u679c", style = TextStyle(color = cp(previewTextColor), fontSize = 14.sp, fontWeight = FontWeight.Bold))
                Text(text = "7\u670817\u65e5 \u767d\u73ed", style = TextStyle(color = cp(previewTextColor.copy(alpha = 0.7f)), fontSize = 11.sp))
            }
        }
        Spacer(modifier = GlanceModifier.height(16.dp))

        // \u5b57\u4f53\u989c\u8272\u9009\u62e9
        Text(text = "\u5b57\u4f53\u989c\u8272", style = TextStyle(color = cp(Color(0xFF424242)), fontSize = 14.sp, fontWeight = FontWeight.Medium), modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            textColorOptions.forEach { opt ->
                val color = hexToColor(opt.hex, Color.Black)
                val isSelected = opt.hex == selectedTextColor
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = GlanceModifier.padding(end = 8.dp)) {
                    Box(
                        modifier = GlanceModifier.width(40.dp).height(40.dp)
                            .background(cp(if (isSelected) Color(0xFF2196F3) else Color(0xFFBDBDBD)))
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = GlanceModifier.width(34.dp).height(34.dp).background(cp(color)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) Text(text = "\u2713", style = TextStyle(color = cp(Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                    Text(text = opt.label, style = TextStyle(color = cp(Color(0xFF757575)), fontSize = 9.sp), modifier = GlanceModifier.padding(top = 2.dp))
                }
            }
        }
        Spacer(modifier = GlanceModifier.height(14.dp))

        // \u80cc\u666f\u989c\u8272\u9009\u62e9
        Text(text = "\u80cc\u666f\u989c\u8272", style = TextStyle(color = cp(Color(0xFF424242)), fontSize = 14.sp, fontWeight = FontWeight.Medium), modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            bgColorOptions.forEach { opt ->
                val color = hexToColor(opt.hex, Color.White)
                val isSelected = opt.hex == selectedBgColor
                val isDark = opt.hex == "#FF263238" || opt.hex == "#FF1A1A1A"
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = GlanceModifier.padding(end = 6.dp)) {
                    Box(
                        modifier = GlanceModifier.width(36.dp).height(36.dp)
                            .background(cp(if (isSelected) Color(0xFF2196F3) else Color(0xFFBDBDBD)))
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = GlanceModifier.width(30.dp).height(30.dp).background(cp(color)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) Text(text = "\u2713", style = TextStyle(color = cp(if (isDark) Color.White else Color.Black), fontSize = 14.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                    Text(text = opt.label, style = TextStyle(color = cp(Color(0xFF757575)), fontSize = 9.sp), modifier = GlanceModifier.padding(top = 2.dp))
                }
            }
        }
        Spacer(modifier = GlanceModifier.height(14.dp))

        // \u80cc\u666f\u900f\u660e\u5ea6
        Text(
            text = "\u80cc\u666f\u900f\u660e\u5ea6\uff1a${(bgTransparency * 100).toInt()}%",
            style = TextStyle(color = cp(Color(0xFF424242)), fontSize = 14.sp, fontWeight = FontWeight.Medium),
            modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)
        )
        val transparencyOptions = listOf(0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f)
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            transparencyOptions.forEach { level ->
                val isThisLevel = bgTransparency == level
                Box(
                    modifier = GlanceModifier.defaultWeight().padding(horizontal = 2.dp).height(32.dp)
                        .background(cp(if (isThisLevel) Color(0xFF2196F3) else Color(0xFFE0E0E0)))
                        .clickable { bgTransparency = level },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${(level * 100).toInt()}",
                        style = TextStyle(color = cp(if (isThisLevel) Color.White else Color(0xFF616161)), fontSize = 10.sp, fontWeight = if (isThisLevel) FontWeight.Bold else FontWeight.Normal)
                    )
                }
            }
        }
        Spacer(modifier = GlanceModifier.height(20.dp))

        // \u4fdd\u5b58\u6309\u94ae
        Box(
            modifier = GlanceModifier.fillMaxWidth().height(44.dp).background(cp(Color(0xFF2196F3)))
                .clickable {
                    prefs.edit()
                        .putString(KEY_CFG_TEXT_COLOR, selectedTextColor)
                        .putString(KEY_CFG_BG_COLOR, selectedBgColor)
                        .putFloat(KEY_CFG_BG_TRANSPARENCY, bgTransparency)
                        .apply()
                    onDone()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(text = "\u4fdd\u5b58\u914d\u7f6e", style = TextStyle(color = cp(Color.White), fontSize = 16.sp, fontWeight = FontWeight.Bold))
        }
    }
}
