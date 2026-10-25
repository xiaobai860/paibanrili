// app/src/main/java/com/schedulecalendar/app/widget/CalendarGlanceWidget.kt
@file:Suppress("RestrictedApi") // Glance 1.2.0 误将 ColorProvider 标为同 library group 限制，此处为已知问题的官方推荐绕过方式

package com.schedulecalendar.app.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import com.schedulecalendar.app.domain.util.AppLog
import com.google.gson.annotations.SerializedName
import com.schedulecalendar.app.MainActivity
import java.time.LocalDate

internal const val CALENDAR_WIDGET_DATA_PREFS = "calendar_widget_data_prefs"
internal const val KEY_CALENDAR_WIDGET_JSON = "calendar_widget_json"

data class CalendarWidgetDay(
    @SerializedName("day") val day: Int = 0,
    @SerializedName("dateStr") val dateStr: String = "",
    @SerializedName("shiftName") val shiftName: String = "",
    @SerializedName("shiftColor") val shiftColor: String = "",
    @SerializedName("statusName") val statusName: String = "",
    // 以下由 refreshCalendarWidgets 在单一同步路径预计算（节假日/农历文本），
    // 组合期只读字符串，不再逐格重算 tyme4j/HolidayData（刷新频繁时省重复计算）
    @SerializedName("isLegalHoliday") val isLegalHoliday: Boolean = false,
    @SerializedName("isMakeupWorkday") val isMakeupWorkday: Boolean = false,
    @SerializedName("isHolidayFirstDay") val isHolidayFirstDay: Boolean = false,
    @SerializedName("subText") val subText: String = ""
)

data class CalendarWidgetInfo(
    @SerializedName("year") val year: Int = 0,
    @SerializedName("month") val month: Int = 0,
    @SerializedName("days") val days: List<CalendarWidgetDay> = emptyList(),
    @SerializedName("weekStartOffset") val weekStartOffset: Int = 0,
    @SerializedName("totalRows") val totalRows: Int = 5,
    // 今日高亮日（0=不高亮）。由 payload 决定，组合期不再用 LocalDate.now() 判高亮；
    // 零点随 refreshCalendarWidgets 重算（其缓存 key 含今天）而更新，保证跨天 Highlight 准时。
    @SerializedName("todayDay") val todayDay: Int = 0
)

class CalendarGlanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val sizes = runCatching { GlanceAppWidgetManager(context).getAppWidgetSizes(id) }.getOrNull()
        val maxH = sizes?.maxOfOrNull { it.height }?.value ?: 180f
        val isLarge = maxH >= 220f
        // 3x3 widget：显示 日期+班次+附加状态（3 项），launcher cell 大时不裁切
        runCatching {
            provideContent {
                CalendarWidgetContent(
                    isLarge = isLarge,
                    showLunar = false,
                    showStatus = true,
                    maxVisibleRows = Int.MAX_VALUE,
                    compact = true
                )
            }
        }.onFailure { AppLog.e("CalendarGlanceWidget", "provideGlance failed", it) }
    }
    companion object {
        suspend fun updateWidgetData(context: Context, data: CalendarWidgetInfo) {
            val gson = Gson()
            val widget = CalendarGlanceWidget()
            val manager = GlanceAppWidgetManager(context)
            context.getSharedPreferences(CALENDAR_WIDGET_DATA_PREFS, Context.MODE_PRIVATE)
                .edit { putString(KEY_CALENDAR_WIDGET_JSON, gson.toJson(data)) }
            manager.getGlanceIds(CalendarGlanceWidget::class.java).forEach { gid ->
                widget.updateSerialized(context, gid)
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

/** 小组件刷新动作回调：回源数据库重新计算并写入数据 */
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        performManualRefresh(context)
    }
}

@Suppress("LocalContextConfigurationRead")
@Composable
private fun CalendarWidgetContent(
    isLarge: Boolean,
    showLunar: Boolean,
    showStatus: Boolean,
    maxVisibleRows: Int = Int.MAX_VALUE,
    compact: Boolean = false
) {
    val prefs = LocalContext.current.getSharedPreferences(CALENDAR_WIDGET_DATA_PREFS, Context.MODE_PRIVATE)
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
    // 窄尺寸（3x3）只显示「10月」：给右侧两个按钮让出宽度，否则刷新图标会被挤出边界点不到
    val headerText = if (data.month > 0) {
        if (compact) "${data.month}月" else "${data.year}年${data.month}月"
    } else {
        if (compact) "${today.monthValue}月" else "${today.year}年${today.monthValue}月"
    }
    val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")
    val useBig = isLarge && !compact
    val wfs = if (useBig) 10.sp else 10.sp
    val dfs = 14.sp
    val sfs = if (useBig) 10.sp else 10.sp
    val statusFontSize = if (useBig) 9.sp else 9.sp
    val lfs = if (isLarge) 8.sp else 9.sp
    val visibleRows = minOf(data.totalRows, maxVisibleRows)
    val headerUseBig = isLarge && !compact
    val headerFs = if (headerUseBig) 17.sp else 14.sp
    Box(
        modifier = GlanceModifier.fillMaxSize()
            .background(ColorProvider(ubg))
            .cornerRadius(6.dp)
            .padding(if (isLarge) 4.dp else 4.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            // Header: 左对齐年月 + 右侧设置/刷新按钮
            // ⚠️ 3x3 很窄（约 110dp）：「2026年10月」+ 齿轮 + 刷新会超出宽度，
            //    刷新图标被挤出组件边界 → **点不到**（真机实测：点击后 action 根本没派发，
            //    日志里没有任何记录；且尺寸越小的实例越明显）。
            //    对策：① 标题占满弹性空间并允许压缩；② compact 尺寸只显示「10月」；
            //         ③ 两个图标用 28dp 的点击热区包 20dp 的视觉图标（更好点中）。
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = headerText,
                    style = TextStyle(color = if (isDark) ColorProvider(utcDark) else ColorProvider(utc), fontSize = headerFs, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                )
                Box(
                    modifier = GlanceModifier.size(28.dp).clickable(
                        actionRunCallback<OpenWidgetConfigAction>(
                            parameters = actionParametersOf(KEY_TYPE to WIDGET_TYPE_CALENDAR)
                        )
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    WidgetIcon(
                        context = context,
                        resId = com.schedulecalendar.app.R.drawable.ic_widget_gear,
                        color = if (isDark) utcDark else utc,
                        modifier = GlanceModifier.size(20.dp)
                    )
                }
                Box(
                    modifier = GlanceModifier.size(28.dp).clickable(actionRunCallback<RefreshWidgetAction>()),
                    contentAlignment = Alignment.Center
                ) {
                    WidgetIcon(
                        context = context,
                        resId = com.schedulecalendar.app.R.drawable.ic_widget_refresh,
                        color = if (isDark) utcDark else utc,
                        modifier = GlanceModifier.size(20.dp)
                    )
                }
            }
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                weekLabels.forEachIndexed { i, label ->
                    Box(modifier = GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) {
                        val lc = if (i >= 5) Color(0xFFDC2626) else utc.copy(alpha = 0.6f)
                        val lcd = if (i >= 5) Color(0xFFEF4444) else utcDark.copy(alpha = 0.6f)
                        Text(text = label, style = TextStyle(color = if (isDark) ColorProvider(lcd) else ColorProvider(lc), fontSize = wfs, fontWeight = FontWeight.Medium))
                    }
                }
            }
            Spacer(modifier = GlanceModifier.height(1.dp))
            val totalDays = data.days.size
            for (row in 0 until visibleRows) {
                Row(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val ci = row * 7 + col
                        val ibm = ci < data.weekStartOffset
                        val mdi = ci - data.weekStartOffset
                        val iam = mdi >= totalDays
                        val ie = ibm || iam
                        if (ie) { Box(modifier = GlanceModifier.defaultWeight()) {} } else {
                            val day = data.days.getOrNull(mdi)
                            val isToday = day != null && data.todayDay > 0 && day.day == data.todayDay
                            val cellCorner = if (isLarge) 4.dp else 4.dp
                            Box(modifier = GlanceModifier.defaultWeight().padding(if (isLarge) 0.5.dp else 0.5.dp)
                                .background(ColorProvider(if (isToday) Color(0xFF2E7D32).copy(alpha = 0.35f * bgAlpha) else Color.Transparent))
                                .cornerRadius(cellCorner)
                            ) {
                                if (day != null && day.day > 0) CalendarDayCellContent(
                                    day, isToday, showLunar, showStatus, dfs, sfs, statusFontSize, lfs, utc, utcDark, bgAlpha, isDark, cellCorner)
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
    day: CalendarWidgetDay, isToday: Boolean,
    showLunar: Boolean, showStatus: Boolean,
    dayNumberSize: TextUnit,
    shiftFontSize: TextUnit, statusFontSize: TextUnit, lunarFontSize: TextUnit,
    textColor: Color, textColorDark: Color, bgAlpha: Float, isDark: Boolean,
    cellCorner: androidx.compose.ui.unit.Dp
) {
    val hasShift = day.shiftName.isNotEmpty()
    val hasStatus = day.statusName.isNotEmpty()
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
        contentAlignment = Alignment.Center) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(vertical = 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            // 1. 日期数字 + 假期标记（节假日/补班标记来自预计算字段）
            Row(verticalAlignment = Alignment.Top) {
                val dayColor = if (isToday) Color(0xFF2E7D32) else textColor
                val dayColorD = if (isToday) Color(0xFF4ADE80) else textColorDark
                Text(text = day.day.toString(),
                    style = TextStyle(color = if (isDark) ColorProvider(dayColorD) else ColorProvider(dayColor), fontSize = dayNumberSize,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium))
                // 法定假「假」/ 补班「补」：日期数字右侧（与数字并排），法定假每天标、补班日标「补」
                if (day.isMakeupWorkday || day.isLegalHoliday) {
                    Text(text = if (day.isMakeupWorkday) "补" else "假",
                        style = TextStyle(color = ColorProvider(if (day.isMakeupWorkday) Color(0xFF2563EB) else Color(0xFFDC2626)), fontSize = 7.sp,
                            fontWeight = FontWeight.Bold))
                }
            }
            // 2. 农历/节假日（仅 3x4 widget 显示；文本已在 refreshCalendarWidgets 预计算，此处只读）
            if (showLunar && day.subText.isNotEmpty()) {
                val isHolidayTitle = day.isHolidayFirstDay && day.isLegalHoliday
                val txtColor = if (isHolidayTitle) Color(0xFFDC2626) else Color(0xFF999999)
                val txtColorD = if (isHolidayTitle) Color(0xFFEF4444) else Color(0xFF777777)
                Text(text = day.subText, style = TextStyle(color = if (isDark) ColorProvider(txtColorD) else ColorProvider(txtColor), fontSize = lunarFontSize), maxLines = 1)
            }
            // 3. 班次名称（始终显示）
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
            // 4. 附加状态名称（仅 3x4 widget 显示）
            if (hasStatus && showStatus) Text(text = day.statusName, style = TextStyle(color = if (isDark) ColorProvider(Color(0xFFFB923C)) else ColorProvider(Color(0xFFF97316)), fontSize = statusFontSize), maxLines = 1)
        }
    }
}

// === 3x4 排班日历小组件 (3 格宽 \u00d7 4 格高，可拉伸到 4 格宽) ===
// 与 3x3 共用 widgetData（读同一份 CalendarWidgetInfo JSON）与样式配置，只是 baseHeight 不同
class Calendar3x4GlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 3x4 widget：显示 4 项内容（日期+班次+附加状态+农历），渲染完整所有行（保证 31 号可见）
        runCatching {
            provideContent {
                CalendarWidgetContent(
                    isLarge = true,
                    showLunar = true,
                    showStatus = true,
                    maxVisibleRows = Int.MAX_VALUE
                )
            }
        }.onFailure { AppLog.e("Calendar3x4GlanceWidget", "provideGlance failed", it) }
    }

    companion object {
        suspend fun updateAllCalendarWidgets(context: Context, data: CalendarWidgetInfo, allowSkipUnchanged: Boolean = false) {
            val json = Gson().toJson(data)
            // P1：周期性后台同步内容未变则跳过（无谓 prefs 写 + Glance 组合 + IPC）；
            // payload 已含 todayDay，跨天会变 → 仍能准时刷新高亮。交互路径传 false 强制推。
            if (allowSkipUnchanged && json == lastPushedCalendarJson) {
                AppLog.d("WIDGET_SYNC", "calendar update skipped (unchanged)")
                return
            }
            context.getSharedPreferences(CALENDAR_WIDGET_DATA_PREFS, Context.MODE_PRIVATE)
                .edit { putString(KEY_CALENDAR_WIDGET_JSON, json) }
            val manager = GlanceAppWidgetManager(context)
            // 3x3 widget
            val ids3x3 = manager.getGlanceIds(CalendarGlanceWidget::class.java)
            ids3x3.forEach { gid ->
                CalendarGlanceWidget().updateSerialized(context, gid)
            }
            // 3x4 widget
            val ids3x4 = manager.getGlanceIds(Calendar3x4GlanceWidget::class.java)
            ids3x4.forEach { gid ->
                Calendar3x4GlanceWidget().updateSerialized(context, gid)
            }
            // 实例数日志：排查「组件没重绘 / 点不到按钮」时，确认更新确实覆盖了所有实例
            AppLog.d(
                "WIDGET_SYNC",
                "calendar widgets updated: 3x3=${ids3x3.size}, 3x4=${ids3x4.size}, " +
                    "month=${data.year}-${data.month}"
            )
            lastPushedCalendarJson = json
        }
    }
}