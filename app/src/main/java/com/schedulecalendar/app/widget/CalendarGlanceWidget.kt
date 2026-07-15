// app/src/main/java/com/schedulecalendar/app/widget/CalendarGlanceWidget.kt
package com.schedulecalendar.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
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
import java.time.LocalDate

// ── 日历小组件数据模型 ─────────────────────────────────────────────

/** 单日格子数据 */
data class CalendarWidgetDay(
    val day: Int = 0,
    val dateStr: String = "",
    val shiftName: String = "",
    val shiftColor: String = "",
    val statusName: String = ""
)

/** 整个日历小组件数据 */
data class CalendarWidgetInfo(
    val year: Int = 0,
    val month: Int = 0,
    val days: List<CalendarWidgetDay> = emptyList(),
    val weekStartOffset: Int = 0,   // 该月1号前需要填充的空白天数
    val totalRows: Int = 5
)

// ── Glance 状态键 ──────────────────────────────────────────────────

internal val KEY_CALENDAR_WIDGET = stringPreferencesKey("calendar_widget_data")

// ── 日历 Glance 小组件 ─────────────────────────────────────────────

class CalendarGlanceWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { CalendarWidgetContent() }
    }

    companion object {
        /** 由 CalendarViewModel 调用，更新所有日历小组件 */
        suspend fun updateWidgetData(context: Context, data: CalendarWidgetInfo) {
            val gson   = Gson()
            val widget = CalendarGlanceWidget()
            val manager = GlanceAppWidgetManager(context)
            manager.getGlanceIds(CalendarGlanceWidget::class.java).forEach { glanceId ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                    prefs.toMutablePreferences().also {
                        it[KEY_CALENDAR_WIDGET] = gson.toJson(data)
                    }
                }
                widget.update(context, glanceId)
            }
        }
    }
}

// ── 点击日期 → 打开 App 跳转到指定日期 ──────────────────────────────

class OpenDateAction : ActionCallback {
    companion object {
        val KEY_DATE = ActionParameters.Key<String>("navigate_date")
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val date = parameters[KEY_DATE] ?: return
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_NAVIGATE_DATE, date)
        }
        context.startActivity(intent)
    }
}

// ── 小组件 UI 内容 ─────────────────────────────────────────────────

@Composable
private fun CalendarWidgetContent() {
    val prefs   = currentState<androidx.datastore.preferences.core.Preferences>()
    val jsonStr = prefs[KEY_CALENDAR_WIDGET]
    val data    = if (!jsonStr.isNullOrBlank())
        runCatching { Gson().fromJson(jsonStr, CalendarWidgetInfo::class.java) }
            .getOrElse { CalendarWidgetInfo() }
    else CalendarWidgetInfo()

    val today = LocalDate.now()
    val isCurrentMonth = data.year == today.year && data.month == today.monthValue
    val headerText = if (data.month > 0) "${data.year}年${data.month}月"
                     else "${today.year}年${today.monthValue}月"
    val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(cp(Color(0xFFF8F9FA)))
            .padding(4.dp)
            .clickable(actionRunCallback<OpenAppAction>()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── 月份标题 ────────────────────────────────────
            Text(
                text  = headerText,
                style = TextStyle(
                    color      = cp(Color(0xFF1A1A1A)),
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.padding(bottom = 2.dp)
            )

            // ── 星期标签行 ──────────────────────────────────
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                weekLabels.forEachIndexed { i, label ->
                    Box(
                        modifier = GlanceModifier.defaultWeight().padding(1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val labelColor = if (i >= 5) Color(0xFFDC2626) else Color(0xFF78909C)
                        Text(
                            text  = label,
                            style = TextStyle(
                                color      = cp(labelColor),
                                fontSize   = 8.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }

            Spacer(modifier = GlanceModifier.height(1.dp))

            // ── 日历网格 ────────────────────────────────────
            val totalDays = data.days.size
            for (row in 0 until data.totalRows) {
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val cellIndex = row * 7 + col
                        val isBeforeMonth = cellIndex < data.weekStartOffset
                        val monthDayIndex = cellIndex - data.weekStartOffset
                        val isAfterMonth  = monthDayIndex >= totalDays
                        val isEmpty = isBeforeMonth || isAfterMonth

                        if (isEmpty) {
                            Box(modifier = GlanceModifier.defaultWeight()) {}
                        } else {
                            val day = data.days.getOrNull(monthDayIndex)
                            val isToday = day != null && isCurrentMonth
                                && day.day == today.dayOfMonth
                            // defaultWeight 必须在 Row content lambda 内调用
                            Box(
                                modifier = GlanceModifier
                                    .defaultWeight()
                                    .padding(0.5.dp)
                            ) {
                                if (day != null && day.day > 0) {
                                    CalendarDayCellContent(day = day, isToday = isToday)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 单日格子内容 ────────────────────────────────────────────────────

@Composable
private fun CalendarDayCellContent(day: CalendarWidgetDay, isToday: Boolean) {
    val hasShift  = day.shiftName.isNotEmpty()
    val hasStatus = day.statusName.isNotEmpty()

    // 背景色：今天用浅绿，有班次用班次色淡色，否则透明
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

    // 点击跳转到对应日期详情页
    val clickAction = actionRunCallback<OpenDateAction>(
        actionParametersOf(OpenDateAction.KEY_DATE to day.dateStr)
    )

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(cp(cellBg))
            .clickable(clickAction),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 日期数字
            val dayColor = when {
                isToday -> Color(0xFF2E7D32)
                else -> Color(0xFF333333)
            }
            Text(
                text  = day.day.toString(),
                style = TextStyle(
                    color      = cp(dayColor),
                    fontSize   = 10.sp,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                )
            )

            // 班次名称（带班次颜色）
            if (hasShift) {
                val shiftHex = day.shiftColor.removePrefix("#")
                val shiftColor = if (shiftHex.length >= 6) {
                    val r = shiftHex.substring(0, 2).toIntOrNull(16) ?: 0x3B
                    val g = shiftHex.substring(2, 4).toIntOrNull(16) ?: 0x82
                    val b = shiftHex.substring(4, 6).toIntOrNull(16) ?: 0xF6
                    Color(r / 255f, g / 255f, b / 255f, 1f)
                } else Color(0xFF3B82F6)

                Text(
                    text     = day.shiftName,
                    style    = TextStyle(
                        color    = cp(shiftColor),
                        fontSize = 7.sp
                    ),
                    maxLines = 1
                )
            }

            // 附加状态名称
            if (hasStatus) {
                Text(
                    text     = day.statusName,
                    style    = TextStyle(
                        color    = cp(Color(0xFFF97316)),
                        fontSize = 6.sp
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

// ── 工具函数 ────────────────────────────────────────────────────────

/** 创建 ColorProvider 的辅助函数，绕过库组限制（与 ScheduleGlanceWidget 保持一致） */
private fun cp(color: Color): ColorProvider = object : ColorProvider {
    override fun getColor(context: Context): Color = color
}
