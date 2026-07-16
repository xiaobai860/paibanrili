// app/src/main/java/com/schedulecalendar/app/widget/ScheduleGlanceWidget.kt
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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
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
import java.time.LocalDate
import java.time.LocalTime

// ── 快捷打卡小组件数据模型 ──────────────────────────────────────────

data class ClockInWidgetData(
    val shiftName: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val tomorrowShiftName: String = "",
    val actualStartTime: String = "",
    val actualEndTime: String = "",
    val shiftColor: String = "#059669"
)

// ── Glance 状态键 ──────────────────────────────────────────────────

internal val KEY_CLOCK_IN_WIDGET = stringPreferencesKey("clock_in_widget_data")

private const val CLOCK_IN_PREFS = "clock_in_widget_prefs"
private const val KEY_CLOCK_IN_DATE = "clock_in_date"
private const val KEY_CLOCK_IN_TIME = "clock_in_time"
private const val KEY_CLOCK_OUT_TIME = "clock_out_time"

// ── 快捷打卡 Glance 小组件 ─────────────────────────────────────────

class ScheduleGlanceWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { ClockInWidgetContent() }
    }

    companion object {
        suspend fun updateWidgetData(context: Context, data: ClockInWidgetData) {
            val gson = Gson()
            val widget = ScheduleGlanceWidget()
            val manager = GlanceAppWidgetManager(context)
            manager.getGlanceIds(ScheduleGlanceWidget::class.java).forEach { glanceId ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                    prefs.toMutablePreferences().also {
                        it[KEY_CLOCK_IN_WIDGET] = gson.toJson(data)
                    }
                }
                widget.update(context, glanceId)
            }
        }
    }
}

// ── 打卡动作回调 ────────────────────────────────────────────────────

class ClockInAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val prefs = context.getSharedPreferences(CLOCK_IN_PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now()
        val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)
        val savedDate = prefs.getString(KEY_CLOCK_IN_DATE, "") ?: ""
        val now = LocalTime.now()
        val currentTime = "%02d:%02d".format(now.hour, now.minute)

        val editor = prefs.edit()
        if (savedDate != todayStr) {
            // 新的一天，记录上班打卡
            editor.putString(KEY_CLOCK_IN_DATE, todayStr)
            editor.putString(KEY_CLOCK_IN_TIME, currentTime)
            editor.remove(KEY_CLOCK_OUT_TIME)
        } else {
            // 同一天，记录下班打卡
            val clockInTime = prefs.getString(KEY_CLOCK_IN_TIME, "") ?: ""
            if (clockInTime.isNotEmpty()) {
                editor.putString(KEY_CLOCK_OUT_TIME, currentTime)
            }
        }
        editor.apply()

        // 刷新小组件
        val widget = ScheduleGlanceWidget()
        widget.update(context, glanceId)
    }
}

// ── 小组件 UI 内容 ─────────────────────────────────────────────────

@Composable
private fun ClockInWidgetContent() {
    val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
    val jsonStr = prefs[KEY_CLOCK_IN_WIDGET]
    val data = if (!jsonStr.isNullOrBlank())
        runCatching { Gson().fromJson(jsonStr, ClockInWidgetData::class.java) }
            .getOrElse { ClockInWidgetData() }
    else ClockInWidgetData()

    val context = LocalContext.current
    val clockPrefs = context.getSharedPreferences(CLOCK_IN_PREFS, Context.MODE_PRIVATE)
    val today = LocalDate.now()
    val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)
    val savedDate = clockPrefs.getString(KEY_CLOCK_IN_DATE, "") ?: ""
    val actualStart = if (savedDate == todayStr) clockPrefs.getString(KEY_CLOCK_IN_TIME, "") ?: "" else ""
    val actualEnd = if (savedDate == todayStr) clockPrefs.getString(KEY_CLOCK_OUT_TIME, "") ?: "" else ""

    // 判断按钮状态
    val hasClockIn = actualStart.isNotEmpty()
    val hasClockOut = actualEnd.isNotEmpty()
    val buttonState = when {
        hasClockOut -> "done"
        hasClockIn -> "clock_out"
        else -> "clock_in"
    }

    val shiftHex = data.shiftColor.removePrefix("#")
    val shiftColor = if (shiftHex.length >= 6) {
        val r = shiftHex.substring(0, 2).toIntOrNull(16) ?: 0x05
        val g = shiftHex.substring(2, 4).toIntOrNull(16) ?: 0x96
        val b = shiftHex.substring(4, 6).toIntOrNull(16) ?: 0x69
        Color(r / 255f, g / 255f, b / 255f, 1f)
    } else Color(0xFF059669)

    // 显示时间：已打卡显示实际时间，否则显示班次时间
    val displayStart = actualStart.ifEmpty { data.startTime }
    val displayEnd = actualEnd.ifEmpty { data.endTime }

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(cp(Color(0xFFF8FAFC)))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧 3/4：班次信息
        Column(
            modifier = GlanceModifier.defaultWeight().padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 当天班次名称
            if (data.shiftName.isNotEmpty()) {
                Text(
                    text = data.shiftName,
                    style = TextStyle(
                        color = cp(shiftColor),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
            } else {
                Text(
                    text = "\u4eca\u65e5\u65e0\u6392\u73ed",
                    style = TextStyle(color = cp(Color(0xFF9E9E9E)), fontSize = 13.sp),
                    maxLines = 1
                )
            }

            // 上下班时间
            if (data.startTime.isNotEmpty() || data.endTime.isNotEmpty()) {
                val timeColor = when {
                    hasClockIn && !hasClockOut -> Color(0xFFF59E0B)
                    hasClockOut -> Color(0xFF10B981)
                    else -> Color(0xFF6B7280)
                }
                Text(
                    text = "$displayStart \u2013 $displayEnd",
                    style = TextStyle(color = cp(timeColor), fontSize = 12.sp),
                    maxLines = 1
                )
            }

            // 明天班次
            if (data.tomorrowShiftName.isNotEmpty()) {
                Text(
                    text = "\u660e\u5929\uff1a${data.tomorrowShiftName}",
                    style = TextStyle(color = cp(Color(0xFF9CA3AF)), fontSize = 10.sp),
                    maxLines = 1
                )
            }
        }

        // 右侧 1/4：打卡按钮
        val (btnText, btnColor) = when (buttonState) {
            "clock_in" -> "\u4e0a\u73ed\n\u6253\u5361" to Color(0xFF059669)
            "clock_out" -> "\u4e0b\u73ed\n\u6253\u5361" to Color(0xFFF59E0B)
            else -> "\u5df2\n\u5b8c\u6210" to Color(0xFF9CA3AF)
        }

        Box(
            modifier = GlanceModifier
                .width(56.dp)
                .height(56.dp)
                .background(cp(btnColor))
                .clickable(
                    if (buttonState != "done") actionRunCallback<ClockInAction>()
                    else actionRunCallback<OpenAppAction>()
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = btnText,
                style = TextStyle(
                    color = cp(Color.White),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 2
            )
        }
    }
}

/** 点击小组件打开 App */
class OpenAppAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        intent?.let { context.startActivity(it) }
    }
}

/** 创建 ColorProvider 的辅助函数 */
private fun cp(color: Color): ColorProvider = object : ColorProvider {
    override fun getColor(context: Context): Color = color
}
