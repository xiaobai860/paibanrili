// app/src/main/java/com/schedulecalendar/app/widget/CalendarGlanceWidget.kt
package com.schedulecalendar.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.google.gson.Gson
import com.schedulecalendar.app.MainActivity
import com.schedulecalendar.app.domain.model.HolidayData
import com.schedulecalendar.app.domain.model.LunarCalendar
import java.time.LocalDate

data class CalendarWidgetDay(
    val day: Int = 0, val dateStr: String = "",
    val shiftName: String = "", val shiftColor: String = "", val statusName: String = ""
)

data class CalendarWidgetInfo(
    val year: Int = 0, val month: Int = 0,
    val days: List<CalendarWidgetDay> = emptyList(),
    val weekStartOffset: Int = 0, val totalRows: Int = 5
)

internal val KEY_CALENDAR_WIDGET = stringPreferencesKey("calendar_widget_data")

class CalendarGlanceWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { CalendarWidgetContent() }
    }
    companion object {
        suspend fun updateWidgetData(context: Context, data: CalendarWidgetInfo) {
            val gson = Gson()
            val widget = CalendarGlanceWidget()
            val manager = GlanceAppWidgetManager(context)
            manager.getGlanceIds(CalendarGlanceWidget::class.java).forEach { gid ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, gid) { p ->
                    p.toMutablePreferences().also { it[KEY_CALENDAR_WIDGET] = gson.toJson(data) }
                }
                widget.update(context, gid)
            }
        }
    }
}

class OpenDateAction : ActionCallback {
    companion object { val KEY_DATE = ActionParameters.Key<String>("navigate_date") }
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val date = parameters[KEY_DATE] ?: return
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_NAVIGATE_DATE, date)
        }
        context.startActivity(intent)
    }
}

@Composable
private fun CalendarWidgetContent() {
    val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
    val jsonStr = prefs[KEY_CALENDAR_WIDGET]
    val data = if (!jsonStr.isNullOrBlank())
        runCatching { Gson().fromJson(jsonStr, CalendarWidgetInfo::class.java) }.getOrElse { CalendarWidgetInfo() }
    else CalendarWidgetInfo()
    val context = LocalContext.current
    val cfgPrefs = context.getSharedPreferences(WIDGET_CONFIG_PREFS, Context.MODE_PRIVATE)
    val textHex = cfgPrefs.getString(KEY_CFG_TEXT_COLOR, "#FF333333") ?: "#FF333333"
    val bgHex = cfgPrefs.getString(KEY_CFG_BG_COLOR, "#FFF5F5F5") ?: "#FFF5F5F5"
    val bgAlpha = cfgPrefs.getFloat(KEY_CFG_BG_TRANSPARENCY, 1.0f)
    val utc = hexToWidgetColor(textHex, Color(0xFF333333))
    val ubg = hexToWidgetColor(bgHex, Color(0xFFF5F5F5)).copy(alpha = bgAlpha)
    val ws = LocalSize.current
    val isLarge = ws.height >= 220.dp
    val today = LocalDate.now()
    val isCurMon = data.year == today.year && data.month == today.monthValue
    val headerText = if (data.month > 0) "${data.year}\u5e74${data.month}\u6708" else "${today.year}\u5e74${today.monthValue}\u6708"
    val weekLabels = listOf("\u4e00", "\u4e8c", "\u4e09", "\u56db", "\u4e94", "\u516d", "\u65e5")
    val tfs = if (isLarge) 16.sp else 14.sp
    val wfs = if (isLarge) 10.sp else 9.sp
    val dfs = if (isLarge) 13.sp else 11.sp
    val sfs = if (isLarge) 9.sp else 8.sp
    val stfs = if (isLarge) 8.sp else 7.sp
    val lfs = 8.sp
    Box(
        modifier = GlanceModifier.fillMaxSize().background(cp(ubg))
            .padding(if (isLarge) 6.dp else 4.dp).clickable(actionRunCallback<OpenAppAction>()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(modifier = GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = headerText, style = TextStyle(color = cp(utc), fontSize = tfs, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.padding(bottom = 2.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                weekLabels.forEachIndexed { i, label ->
                    Box(modifier = GlanceModifier.defaultWeight().padding(1.dp), contentAlignment = Alignment.Center) {
                        val lc = if (i >= 5) Color(0xFFDC2626) else utc.copy(alpha = 0.6f)
                        Text(text = label, style = TextStyle(color = cp(lc), fontSize = wfs, fontWeight = FontWeight.Medium))
                    }
                }
            }
            Spacer(modifier = GlanceModifier.height(if (isLarge) 2.dp else 1.dp))
            val totalDays = data.days.size
            for (row in 0 until data.totalRows) {
                Row(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val ci = row * 7 + col
                        val ibm = ci < data.weekStartOffset
                        val mdi = ci - data.weekStartOffset
                        val iam = mdi >= totalDays
                        val ie = ibm || iam
                        if (ie) { Box(modifier = GlanceModifier.defaultWeight()) {} } else {
                            val day = data.days.getOrNull(mdi)
                            val isToday = day != null && isCurMon && day.day == today.dayOfMonth
                            Box(modifier = GlanceModifier.defaultWeight().padding(if (isLarge) 1.dp else 0.5.dp)) {
                                if (day != null && day.day > 0) CalendarDayCellContent(
                                    day, isToday, isLarge, data.year, data.month, dfs, sfs, stfs, lfs, utc)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCellContent(
    day: CalendarWidgetDay, isToday: Boolean, isLarge: Boolean,
    year: Int, month: Int, dayNumberSize: TextUnit,
    shiftFontSize: TextUnit, statusFontSize: TextUnit, lunarFontSize: TextUnit, textColor: Color
) {
    val hasShift = day.shiftName.isNotEmpty()
    val hasStatus = day.statusName.isNotEmpty()
    val dateStr = if (year > 0 && month > 0) "%04d-%02d-%02d".format(year, month, day.day) else ""
    val isLegalHoliday = dateStr.isNotEmpty() && HolidayData.isLegalHoliday(dateStr)
    val isHolidayFirstDay = if (isLegalHoliday) {
        val prevDate = try {
            val p = LocalDate.of(year, month, day.day).minusDays(1)
            "%04d-%02d-%02d".format(p.year, p.monthValue, p.dayOfMonth)
        } catch (_: Exception) { null }
        prevDate == null || !HolidayData.isLegalHoliday(prevDate)
    } else false
    val cellBg: Color = when {
        isToday -> Color(0xFFE8F5E9)
        hasShift -> {
            val hex = day.shiftColor.removePrefix("#")
            if (hex.length >= 6) {
                val r = hex.substring(0, 2).toIntOrNull(16) ?: 0
                val g = hex.substring(2, 4).toIntOrNull(16) ?: 0
                val b = hex.substring(4, 6).toIntOrNull(16) ?: 0
                Color(r / 255f, g / 255f, b / 255f, 0.12f)
            } else Color.Transparent
        }
        else -> Color.Transparent
    }
    val clickAction = actionRunCallback<OpenDateAction>(
        actionParametersOf(OpenDateAction.KEY_DATE to day.dateStr))
    Box(modifier = GlanceModifier.fillMaxSize().background(cp(cellBg)).clickable(clickAction),
        contentAlignment = Alignment.TopCenter) {
        Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = if (isLarge) 2.dp else 1.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            // Date number + holiday badge in Row
            Row(verticalAlignment = Alignment.Top) {
                val dayColor = if (isToday) Color(0xFF2E7D32) else textColor
                Text(text = day.day.toString(),
                    style = TextStyle(color = cp(dayColor), fontSize = dayNumberSize,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal))
                if (isLegalHoliday && isHolidayFirstDay) {
                    Text(text = "\u5047",
                        style = TextStyle(color = cp(Color(0xFFDC2626)), fontSize = 7.sp,
                            fontWeight = FontWeight.Bold))
                }
            }
            // Lunar/festival (only large mode, above shift name)
            if (isLarge && year > 0 && month > 0) {
                val holidayName = if (isLegalHoliday) HolidayData.getHolidayName(dateStr) else null
                val festivalInfo = runCatching { HolidayData.getFullFestivalInfo(dateStr) }.getOrDefault(emptyList())
                val lunarText = runCatching { LunarCalendar.getLunarDayText(year, month, day.day) }.getOrDefault("")
                val displayText = when {
                    isHolidayFirstDay && holidayName != null -> holidayName
                    festivalInfo.isNotEmpty() -> festivalInfo.first()
                    lunarText.isNotEmpty() -> lunarText
                    else -> ""
                }
                if (displayText.isNotEmpty()) {
                    val txtColor = if (isHolidayFirstDay && holidayName != null) Color(0xFFDC2626) else Color(0xFF9E9E9E)
                    Text(text = displayText, style = TextStyle(color = cp(txtColor), fontSize = lunarFontSize), maxLines = 1)
                }
            }
            if (hasShift) {
                val shiftHex = day.shiftColor.removePrefix("#")
                val shiftColor = if (shiftHex.length >= 6) {
                    val r = shiftHex.substring(0, 2).toIntOrNull(16) ?: 0x3B
                    val g = shiftHex.substring(2, 4).toIntOrNull(16) ?: 0x82
                    val b = shiftHex.substring(4, 6).toIntOrNull(16) ?: 0xF6
                    Color(r / 255f, g / 255f, b / 255f, 1f)
                } else Color(0xFF3B82F6)
                Text(text = day.shiftName, style = TextStyle(color = cp(shiftColor), fontSize = shiftFontSize), maxLines = 1)
            }
            if (hasStatus) Text(text = day.statusName, style = TextStyle(color = cp(Color(0xFFF97316)), fontSize = statusFontSize), maxLines = 1)
        }
    }
}

private fun cp(color: Color): ColorProvider = object : ColorProvider {
    override fun getColor(context: Context): Color = color
}

private fun hexToWidgetColor(hex: String, fallback: Color): Color {
    return runCatching {
        val h = hex.removePrefix("#")
        val a = if (h.length == 8) h.substring(0, 2).toInt(16) / 255f else 1f
        val r = h.substring(h.length - 6, h.length - 4).toInt(16) / 255f
        val g = h.substring(h.length - 4, h.length - 2).toInt(16) / 255f
        val b = h.substring(h.length - 2).toInt(16) / 255f
        Color(r, g, b, a)
    }.getOrElse { fallback }
}
