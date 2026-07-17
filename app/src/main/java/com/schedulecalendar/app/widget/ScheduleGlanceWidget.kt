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
import com.schedulecalendar.app.domain.model.HolidayData
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

// ── 打卡动作回调（始终可点击，更新打卡时间） ─────────────────────────

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
            // 新的一天 → 上班打卡
            editor.putString(KEY_CLOCK_IN_DATE, todayStr)
            editor.putString(KEY_CLOCK_IN_TIME, currentTime)
            editor.remove(KEY_CLOCK_OUT_TIME)
        } else {
            val clockInTime = prefs.getString(KEY_CLOCK_IN_TIME, "") ?: ""
            val clockOutTime = prefs.getString(KEY_CLOCK_OUT_TIME, "") ?: ""
            if (clockOutTime.isEmpty() && clockInTime.isNotEmpty()) {
                // 已打卡上班 → 下班打卡
                editor.putString(KEY_CLOCK_OUT_TIME, currentTime)
            } else {
                // 已打卡下班 → 更新下班时间（可重复点击）
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

/** 将十六进制颜色字符串转为 Glance ColorProvider */
private fun parseShiftColor(hex: String): ColorProvider {
    val h = hex.removePrefix("#")
    val color = if (h.length >= 6) {
        val r = h.substring(0, 2).toIntOrNull(16) ?: 0x05
        val g = h.substring(2, 4).toIntOrNull(16) ?: 0x96
        val b = h.substring(4, 6).toIntOrNull(16) ?: 0x69
        Color(r / 255f, g / 255f, b / 255f, 1f)
    } else Color(0xFF059669)
    return cp(color)
}

/** 计算下一个法定节假日天数 */
private fun getHolidayCountdownText(): String {
    val today = LocalDate.now()
    val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)

    // 检查当天是否是法定节假日
    if (HolidayData.isLegalHoliday(todayStr)) {
        val name = HolidayData.getHolidayName(todayStr)
        return if (name != null && name !in listOf("春节补班", "劳动节补班", "端午节补班", "中秋节补班", "国庆节补班"))
            "\u4eca\u5929${name}\u5c31\u662f\uff01" else "\u4eca\u65e5\u505c\u5de5"
    }

    // 查找下一个最近法定节假日首日
    val (holidayName, daysUntil) = HolidayData.getNextHolidayCountdown(todayStr)
    return if (daysUntil > 0) {
        "\u8ddd${holidayName}\u8fd8\u6709${daysUntil}\u5929"
    } else if (daysUntil == 0) {
        "\u4eca\u5929${holidayName}\u5c31\u662f\uff01"
    } else {
        ""
    }
}

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

    // 判断打卡状态
    val hasClockIn = actualStart.isNotEmpty()
    val hasClockOut = actualEnd.isNotEmpty()

    // 读取配置的显示模式
    val configPrefs = context.getSharedPreferences(WIDGET_CONFIG_PREFS, Context.MODE_PRIVATE)
    val displayMode = configPrefs.getString(
        KEY_CFG_DISPLAY_MODE, DISPLAY_MODE_SHIFT_TOMORROW
    ) ?: DISPLAY_MODE_SHIFT_TOMORROW
    val textHex = configPrefs.getString(KEY_CFG_TEXT_COLOR, "#FF333333") ?: "#FF333333"
    val bgHex = configPrefs.getString(KEY_CFG_BG_COLOR, "#FFF5F5F5") ?: "#FFF5F5F5"
    val bgAlpha = configPrefs.getFloat(KEY_CFG_BG_TRANSPARENCY, 1.0f)
    val utc = hexToWidgetColor(textHex, Color(0xFF333333))
    val ubg = hexToWidgetColor(bgHex, Color(0xFFF5F5F5)).copy(alpha = bgAlpha)

    // 解析班次颜色
    val shiftColor = parseShiftColor(data.shiftColor)

    // 显示时间：已打卡显示实际时间，否则显示班次时间
    val displayStart = actualStart.ifEmpty { data.startTime }
    val displayEnd = actualEnd.ifEmpty { data.endTime }

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(cp(ubg))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── 左侧：班次信息（点击跳转主页面） ──
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .padding(end = 6.dp)
                .clickable(actionRunCallback<OpenAppAction>()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 当天班次名称
            if (data.shiftName.isNotEmpty()) {
                Text(
                    text = data.shiftName,
                    style = TextStyle(
                        color = shiftColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
            } else {
                Text(
                    text = "\u4eca\u65e5\u65e0\u6392\u73ed",
                    style = TextStyle(color = cp(utc.copy(alpha = 0.6f)), fontSize = 13.sp),
                    maxLines = 1
                )
            }

            // 上下班时间
            if (data.startTime.isNotEmpty() || data.endTime.isNotEmpty()) {
                val timeColor = when {
                    hasClockIn && !hasClockOut -> cp(Color(0xFFF59E0B))
                    hasClockOut -> cp(Color(0xFF10B981))
                    else -> cp(utc.copy(alpha = 0.6f))
                }
                Text(
                    text = "$displayStart \u2013 $displayEnd",
                    style = TextStyle(color = timeColor, fontSize = 12.sp),
                    maxLines = 1
                )
            }

            // 第二行：根据显示模式展示不同内容
            when (displayMode) {
                DISPLAY_MODE_SHIFT_HOLIDAY -> {
                    // 模式：法定节假日倒计时
                    val countdownText = getHolidayCountdownText()
                    if (countdownText.isNotEmpty()) {
                        Text(
                            text = countdownText,
                            style = TextStyle(
                                color = cp(Color(0xFFDC2626)),
                                fontSize = 10.sp
                            ),
                            maxLines = 1
                        )
                    }
                }
                else -> {
                    // 模式：明天班次（默认）
                    if (data.tomorrowShiftName.isNotEmpty()) {
                        Text(
                            text = "\u660e\u5929\uff1a${data.tomorrowShiftName}",
                            style = TextStyle(
                                color = cp(utc.copy(alpha = 0.6f)),
                                fontSize = 10.sp
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // ── 右侧：打卡按钮（缩小宽度，始终可点击） ──
        val btnText: String
        val btnColor: ColorProvider
        when {
            !hasClockIn -> {
                btnText = "\u4e0a\u73ed\n\u6253\u5361"
                btnColor = cp(Color(0xFF059669))
            }
            !hasClockOut -> {
                btnText = "\u4e0b\u73ed\n\u6253\u5361"
                btnColor = cp(Color(0xFFF59E0B))
            }
            else -> {
                btnText = actualEnd.take(5)
                btnColor = cp(Color(0xFF9CA3AF))
            }
        }

        Box(
            modifier = GlanceModifier
                .width(40.dp)
                .height(40.dp)
                .background(btnColor)
                .clickable(actionRunCallback<ClockInAction>()),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = btnText,
                style = TextStyle(
                    color = cp(Color.White),
                    fontSize = 10.sp,
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
