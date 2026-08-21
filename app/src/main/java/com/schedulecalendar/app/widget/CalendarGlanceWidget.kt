// app/src/main/java/com/schedulecalendar/app/widget/CalendarGlanceWidget.kt
package com.schedulecalendar.app.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.core.content.edit
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import com.schedulecalendar.app.widget.OpenWidgetConfigAction.Companion.KEY_TYPE
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.google.gson.Gson
import com.schedulecalendar.app.MainActivity
import com.schedulecalendar.app.domain.model.HolidayData
import com.schedulecalendar.app.domain.model.LunarCalendar
import java.time.LocalDate

private const val CALENDAR_WIDGET_DATA_PREFS = "calendar_widget_data_prefs"
private const val KEY_CALENDAR_WIDGET_JSON = "calendar_widget_json"

data class CalendarWidgetDay(
    val day: Int = 0, val dateStr: String = "",
    val shiftName: String = "", val shiftColor: String = "", val statusName: String = ""
)

data class CalendarWidgetInfo(
    val year: Int = 0, val month: Int = 0,
    val days: List<CalendarWidgetDay> = emptyList(),
    val weekStartOffset: Int = 0, val totalRows: Int = 5
)

class CalendarGlanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val sizes = runCatching { GlanceAppWidgetManager(context).getAppWidgetSizes(id) }.getOrNull()
        val maxH = sizes?.maxOfOrNull { it.height }?.value ?: 180f
        val isLarge = maxH >= 220f
        val baseHeight = maxH.dp
        provideContent { CalendarWidgetContent(isLarge, baseHeight) }
    }
    companion object {
        suspend fun updateWidgetData(context: Context, data: CalendarWidgetInfo) {
            val gson = Gson()
            val widget = CalendarGlanceWidget()
            val manager = GlanceAppWidgetManager(context)
            context.getSharedPreferences(CALENDAR_WIDGET_DATA_PREFS, Context.MODE_PRIVATE)
                .edit { putString(KEY_CALENDAR_WIDGET_JSON, gson.toJson(data)) }
            manager.getGlanceIds(CalendarGlanceWidget::class.java).forEach { gid ->
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

/** 小组件刷新动作回调：触发 UI 重渲染（数据同步由 APP ViewModel 负责） */
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // 仅触发 UI 重渲染，从 Glance DataStore 状态中重新加载已有数据
        // 数据同步由 CalendarViewModel.syncCalendarWidget 负责，确保数据源一致
        CalendarGlanceWidget().update(context, glanceId)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "\u5237\u65b0\u6210\u529f", Toast.LENGTH_SHORT).show()
        }
    }
}

@Suppress("LocalContextConfigurationRead")
@Composable
private fun CalendarWidgetContent(isLarge: Boolean, baseHeight: androidx.compose.ui.unit.Dp) {
    val prefs = androidx.glance.LocalContext.current.getSharedPreferences(CALENDAR_WIDGET_DATA_PREFS, Context.MODE_PRIVATE)
    val jsonStr = prefs.getString(KEY_CALENDAR_WIDGET_JSON, "")
    val data = if (!jsonStr.isNullOrBlank())
        runCatching { Gson().fromJson(jsonStr, CalendarWidgetInfo::class.java) }.getOrElse { CalendarWidgetInfo() }
    else CalendarWidgetInfo()
    val context = LocalContext.current
    val cfgPrefs = context.getSharedPreferences(WIDGET_CONFIG_PREFS, Context.MODE_PRIVATE)
    val textHex = cfgPrefs.getString(KEY_CFG_TEXT_COLOR, "#FF333333") ?: "#FF333333"
    val bgHex = cfgPrefs.getString(KEY_CFG_BG_COLOR, "#FFFFFFFF") ?: "#FFFFFFFF"
    val bgTransparency = cfgPrefs.getFloat(KEY_CFG_CALENDAR_BG_TRANSPARENCY,
        cfgPrefs.getFloat(KEY_CFG_BG_TRANSPARENCY, 0.0f))
    val bgAlpha = 1.0f - bgTransparency  // 0%=不透明，100%=全透明
    val utc = hexToWidgetColor(textHex, Color(0xFF333333))
    val ubg = hexToWidgetColor(bgHex, Color.White).copy(alpha = bgAlpha)
    // 深色模式文字
    val utcDark = hexToWidgetColor(textHex, Color(0xFFE0E0E0)).copy(alpha = bgAlpha.coerceAtLeast(0.5f))
    // 检测深色模式
    val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val today = LocalDate.now()
    val isCurMon = data.year == today.year && data.month == today.monthValue
    val headerText = if (data.month > 0) "${data.year}\u5e74${data.month}\u6708" else "${today.year}\u5e74${today.monthValue}\u6708"
    val weekLabels = listOf("\u4e00", "\u4e8c", "\u4e09", "\u56db", "\u4e94", "\u516d", "\u65e5")
    val tfs = if (isLarge) 15.sp else 13.sp
    val wfs = if (isLarge) 9.sp else 8.sp
    val dfs = if (isLarge) 12.sp else 10.sp
    val sfs = if (isLarge) 8.sp else 7.sp
    val stfs = if (isLarge) 7.sp else 6.sp
    val lfs = 7.sp
    // 显示策略（基于行数，确保不裁切）：
    // - 3 行（3x3）：空间不足，仅显示 日期 + 班次，隐藏附加状态（避免露出部分头部）
    // - 4 行（4xN）：空间足够显示农历，显示 日期 + 班次 + 农历，隐藏附加状态（容纳农历）
    // - 5 行以上：都显示
    val heightPerRow = (baseHeight - 34.dp) / data.totalRows.coerceAtLeast(1)
    val showLunar  = data.totalRows >= 4 && heightPerRow >= 40.dp
    val showStatus = data.totalRows >= 5 || (data.totalRows <= 3 && heightPerRow >= 50.dp)
    val headerFs = if (isLarge) 17.sp else 14.sp
    val refreshSize = if (isLarge) 28.dp else 24.dp
    val refreshIconFs = if (isLarge) 18.sp else 15.sp
    Box(
        modifier = GlanceModifier.fillMaxSize()
            .background(ColorProvider(ubg))
            .cornerRadius(16.dp)
            .padding(if (isLarge) 8.dp else 5.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            // Header: 左对齐年月 + 右侧刷新按钮
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = headerText,
                    style = TextStyle(color = if (isDark) ColorProvider(utcDark) else ColorProvider(utc), fontSize = headerFs, fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                Box(
                    modifier = GlanceModifier
                        .width(refreshSize).height(refreshSize)
                        .background(ColorProvider(utc.copy(alpha = 0.08f * bgAlpha)))
                        .cornerRadius(8.dp)
                        .clickable(
                            actionRunCallback<OpenWidgetConfigAction>(
                                parameters = actionParametersOf(KEY_TYPE to WIDGET_TYPE_CALENDAR)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "\u2699",
                        style = TextStyle(color = if (isDark) ColorProvider(utcDark) else ColorProvider(utc), fontSize = refreshIconFs, fontWeight = FontWeight.Bold)
                    )
                }
                Spacer(modifier = GlanceModifier.width(4.dp))
                Box(
                    modifier = GlanceModifier
                        .width(refreshSize).height(refreshSize)
                        .background(ColorProvider(utc.copy(alpha = 0.08f * bgAlpha)))
                        .cornerRadius(8.dp)
                        .clickable(actionRunCallback<RefreshWidgetAction>()),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "\u21bb",
                        style = TextStyle(color = if (isDark) ColorProvider(utcDark) else ColorProvider(utc), fontSize = refreshIconFs, fontWeight = FontWeight.Bold)
                    )
                }
            }
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                weekLabels.forEachIndexed { i, label ->
                    Box(modifier = GlanceModifier.defaultWeight().padding(1.dp), contentAlignment = Alignment.Center) {
                        val lc = if (i >= 5) Color(0xFFDC2626) else utc.copy(alpha = 0.55f)
                        val lcd = if (i >= 5) Color(0xFFEF4444) else utcDark.copy(alpha = 0.55f)
                        Text(text = label, style = TextStyle(color = if (isDark) ColorProvider(lcd) else ColorProvider(lc), fontSize = wfs, fontWeight = FontWeight.Medium))
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
                            val cellCorner = if (isLarge) 6.dp else 4.dp
                            Box(modifier = GlanceModifier.defaultWeight().padding(if (isLarge) 1.5.dp else 1.dp)
                                .background(ColorProvider(if (isToday) Color(0xFF2E7D32).copy(alpha = 0.35f * bgAlpha) else Color.Transparent))
                                .cornerRadius(cellCorner)
                            ) {
                                if (day != null && day.day > 0) CalendarDayCellContent(
                                    day, isToday, isLarge, showLunar, showStatus, data.year, data.month, dfs, sfs, stfs, lfs, utc, utcDark, bgAlpha, isDark, cellCorner)
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
    showLunar: Boolean, showStatus: Boolean,
    year: Int, month: Int, dayNumberSize: TextUnit,
    shiftFontSize: TextUnit, statusFontSize: TextUnit, lunarFontSize: TextUnit,
    textColor: Color, textColorDark: Color, bgAlpha: Float, isDark: Boolean,
    cellCorner: androidx.compose.ui.unit.Dp
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
        isToday -> Color.Transparent  // 今日高亮由外层 Box 负责
        hasShift -> {
            val hex = day.shiftColor.removePrefix("#")
            if (hex.length >= 6) {
                val r = hex.substring(0, 2).toIntOrNull(16) ?: 0
                val g = hex.substring(2, 4).toIntOrNull(16) ?: 0
                val b = hex.substring(4, 6).toIntOrNull(16) ?: 0
                Color(r / 255f, g / 255f, b / 255f, 0.12f * bgAlpha)
            } else Color.Transparent
        }
        else -> Color.Transparent
    }
    val clickAction = actionRunCallback<OpenDateAction>(
        actionParametersOf(OpenDateAction.KEY_DATE to day.dateStr))
    Box(modifier = GlanceModifier.fillMaxSize()
        .background(ColorProvider(cellBg))
        .cornerRadius(cellCorner)
        .clickable(clickAction),
        contentAlignment = Alignment.TopCenter) {
        Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = if (isLarge) 2.dp else 1.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            // 1. 日期数字 + 假期标记
            Row(verticalAlignment = Alignment.Top) {
                val dayColor = if (isToday) Color(0xFF2E7D32) else textColor
                val dayColorD = if (isToday) Color(0xFF4ADE80) else textColorDark
                Text(text = day.day.toString(),
                    style = TextStyle(color = if (isDark) ColorProvider(dayColorD) else ColorProvider(dayColor), fontSize = dayNumberSize,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal))
                if (isLegalHoliday && isHolidayFirstDay) {
                    Text(text = "\u5047",
                        style = TextStyle(color = ColorProvider(Color(0xFFDC2626)), fontSize = 6.sp,
                            fontWeight = FontWeight.Bold))
                }
            }
            // 2. 农历/节假日（仅 isLarge 时显示，确保不挤占班次空间）
            if (showLunar && year > 0 && month > 0) {
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
                    val txtColor = if (isHolidayFirstDay && holidayName != null) Color(0xFFDC2626) else Color(0xFF999999)
                    val txtColorD = if (isHolidayFirstDay && holidayName != null) Color(0xFFEF4444) else Color(0xFF777777)
                    Text(text = displayText, style = TextStyle(color = if (isDark) ColorProvider(txtColorD) else ColorProvider(txtColor), fontSize = lunarFontSize), maxLines = 1)
                }
            }
            // 3. 班次名称
            if (hasShift) {
                val shiftHex = day.shiftColor.removePrefix("#")
                val shiftColor = if (shiftHex.length >= 6) {
                    val r = shiftHex.substring(0, 2).toIntOrNull(16) ?: 0x3B
                    val g = shiftHex.substring(2, 4).toIntOrNull(16) ?: 0x82
                    val b = shiftHex.substring(4, 6).toIntOrNull(16) ?: 0xF6
                    Color(r / 255f, g / 255f, b / 255f, 1f)
                } else Color(0xFF3B82F6)
                Text(text = day.shiftName, style = TextStyle(color = if (isDark) ColorProvider(shiftColor.copy(alpha = 0.85f)) else ColorProvider(shiftColor), fontSize = shiftFontSize), maxLines = 1)
            }
            // 4. 附加状态名称（根据行数/高度判断是否显示，避免3行小组件露出部分头部）
            if (hasStatus && showStatus) Text(text = day.statusName, style = TextStyle(color = if (isDark) ColorProvider(Color(0xFFFB923C)) else ColorProvider(Color(0xFFF97316)), fontSize = statusFontSize), maxLines = 1)
        }
    }
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
