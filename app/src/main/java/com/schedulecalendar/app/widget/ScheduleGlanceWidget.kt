// app/src/main/java/com/schedulecalendar/app/widget/ScheduleGlanceWidget.kt
package com.schedulecalendar.app.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
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
    val shiftColor: String = "#059669",
    val statusName: String = ""
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

        prefs.edit {
            if (savedDate != todayStr) {
                // 新的一天 → 上班打卡
                putString(KEY_CLOCK_IN_DATE, todayStr)
                putString(KEY_CLOCK_IN_TIME, currentTime)
                remove(KEY_CLOCK_OUT_TIME)
            } else {
                val clockInTime = prefs.getString(KEY_CLOCK_IN_TIME, "") ?: ""
                val clockOutTime = prefs.getString(KEY_CLOCK_OUT_TIME, "") ?: ""
                if (clockOutTime.isEmpty() && clockInTime.isNotEmpty()) {
                    // 已打卡上班 → 下班打卡
                    putString(KEY_CLOCK_OUT_TIME, currentTime)
                } else {
                    // 已打卡下班 → 更新下班时间（可重复点击）
                    putString(KEY_CLOCK_OUT_TIME, currentTime)
                }
            }
        }

        // 刷新小组件
        val widget = ScheduleGlanceWidget()
        widget.update(context, glanceId)
        // 同时刷新日历小组件
        CalendarGlanceWidget().let { w ->
            GlanceAppWidgetManager(context).getGlanceIds(w.javaClass).forEach { w.update(context, it) }
        }

        // Toast提示
        val isClockIn = savedDate != todayStr
        val toastMsg = if (isClockIn) "\u5df2\u6210\u529f\u6253\u5361" else "\u5df2\u66f4\u65b0\u6253\u5361"
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
        }
    }
}

// ── 小组件 UI 内容 ─────────────────────────────────────────────────

/** 将十六进制颜色字符串转为 Glance ColorProvider（含深色模式适配） */
private fun parseShiftColor(hex: String, isDark: Boolean): ColorProvider {
    val h = hex.removePrefix("#")
    val lightColor = if (h.length >= 6) {
        val r = h.substring(0, 2).toIntOrNull(16) ?: 0x05
        val g = h.substring(2, 4).toIntOrNull(16) ?: 0x96
        val b = h.substring(4, 6).toIntOrNull(16) ?: 0x69
        Color(r / 255f, g / 255f, b / 255f, 1f)
    } else Color(0xFF059669)
    return if (isDark) ColorProvider(lightColor.copy(alpha = 0.85f)) else ColorProvider(lightColor)
}

/** 根据深色模式选择颜色 */
private fun pickColor(light: Color, dark: Color, isDark: Boolean): ColorProvider {
    return ColorProvider(if (isDark) dark else light)
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

@Suppress("LocalContextConfigurationRead")
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
    val bgHex = configPrefs.getString(KEY_CFG_BG_COLOR, "#FFFFFFFF") ?: "#FFFFFFFF"
    val bgTransparency = configPrefs.getFloat(KEY_CFG_SCHEDULE_BG_TRANSPARENCY,
        configPrefs.getFloat(KEY_CFG_BG_TRANSPARENCY, 0.0f))
    val bgAlpha = 1.0f - bgTransparency  // 0%=不透明，100%=全透明
    val utc = hexToWidgetColor(textHex, Color(0xFF333333))
    val ubg = hexToWidgetColor(bgHex, Color.White).copy(alpha = bgAlpha)
    // 检测深色模式
    val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    // 深色模式文字
    val utcDark = hexToWidgetColor(textHex, Color(0xFFE0E0E0)).copy(alpha = bgAlpha.coerceAtLeast(0.5f))

    // 解析班次颜色
    val shiftColor = parseShiftColor(data.shiftColor, isDark)

    // 显示时间：已打卡显示实际时间，否则显示班次时间
    val displayStart = actualStart.ifEmpty { data.startTime }
    val displayEnd = actualEnd.ifEmpty { data.endTime }

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(ubg))
            .cornerRadius(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── 左侧：班次信息（点击跳转主页面） ──
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .padding(start = 8.dp, end = 4.dp)
                .clickable(actionRunCallback<OpenAppAction>()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 第一行：班次名 + 附加状态（同一行）
            val shiftDisplayName = buildString {
                if (data.shiftName.isNotEmpty()) append(data.shiftName)
                if (data.statusName.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(data.statusName)
                }
            }
            if (shiftDisplayName.isNotEmpty()) {
                Text(
                    text = shiftDisplayName,
                    style = TextStyle(
                        color = shiftColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
            } else {
                Text(
                    text = "\u4eca\u65e5\u65e0\u6392\u73ed",
                    style = TextStyle(color = pickColor(utc.copy(alpha = 0.55f), utcDark.copy(alpha = 0.55f), isDark), fontSize = 13.sp),
                    maxLines = 1
                )
            }

            // 第二行：上下班时间
            if (data.startTime.isNotEmpty() || data.endTime.isNotEmpty()) {
                val timeColor = when {
                    hasClockIn && !hasClockOut -> pickColor(Color(0xFFF59E0B), Color(0xFFFBBF24), isDark)
                    hasClockOut -> pickColor(Color(0xFF10B981), Color(0xFF4ADE80), isDark)
                    else -> pickColor(utc.copy(alpha = 0.55f), utcDark.copy(alpha = 0.55f), isDark)
                }
                Text(
                    text = "$displayStart \u2013 $displayEnd",
                    style = TextStyle(color = timeColor, fontSize = 12.sp),
                    maxLines = 1
                )
            }

            // 第三行：根据显示模式展示不同内容
            when (displayMode) {
                DISPLAY_MODE_SHIFT_HOLIDAY -> {
                    val countdownText = getHolidayCountdownText()
                    if (countdownText.isNotEmpty()) {
                        Text(
                            text = countdownText,
                            style = TextStyle(
                                color = pickColor(Color(0xFFDC2626), Color(0xFFEF4444), isDark),
                                fontSize = 10.sp
                            ),
                            maxLines = 1
                        )
                    }
                }
                else -> {
                    if (data.tomorrowShiftName.isNotEmpty()) {
                        Text(
                            text = "\u660e\u5929\uff1a${data.tomorrowShiftName}",
                            style = TextStyle(
                                color = pickColor(utc.copy(alpha = 0.55f), utcDark.copy(alpha = 0.55f), isDark),
                                fontSize = 10.sp
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // ── 右侧：打卡按钮 ──
        val btnLabel: String   // 上班卡 / 下班卡
        val btnTime: String    // 时间（未打卡显示班次时间）
        val btnBgColor: ColorProvider
        val btnTextColor: ColorProvider
        when {
            !hasClockIn -> {
                btnLabel = "\u4e0a\u73ed\u5361"
                btnTime = ""
                btnBgColor = pickColor(Color(0xFF059669).copy(alpha = 0.12f * bgAlpha), Color(0xFF059669).copy(alpha = 0.22f), isDark)
                btnTextColor = pickColor(Color(0xFF059669), Color(0xFF4ADE80), isDark)
            }
            !hasClockOut -> {
                btnLabel = "\u4e0b\u73ed\u5361"
                btnTime = actualStart.take(5)
                btnBgColor = pickColor(Color(0xFFF59E0B).copy(alpha = 0.12f * bgAlpha), Color(0xFFF59E0B).copy(alpha = 0.22f), isDark)
                btnTextColor = pickColor(Color(0xFFD97706), Color(0xFFFBBF24), isDark)
            }
            else -> {
                btnLabel = "\u4e0b\u73ed\u5361"
                btnTime = actualEnd.take(5)
                btnBgColor = pickColor(Color(0xFF9CA3AF).copy(alpha = 0.10f * bgAlpha), Color(0xFF9CA3AF).copy(alpha = 0.18f), isDark)
                btnTextColor = pickColor(Color(0xFF6B7280), Color(0xFF9CA3AF), isDark)
            }
        }

        Box(
            modifier = GlanceModifier
                .width(40.dp)
                .height(36.dp)
                .background(btnBgColor)
                .cornerRadius(10.dp)
                .clickable(actionRunCallback<ClockInAction>()),
            contentAlignment = Alignment.Center
        ) {
            if (btnTime.isEmpty()) {
                // 未打卡：单行居中显示"上班卡"
                Text(
                    text = btnLabel,
                    style = TextStyle(
                        color = btnTextColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    ),
                    maxLines = 1
                )
            } else {
                // 已打卡：两行居中显示"下班卡" + 时间
                Column(
                    modifier = GlanceModifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = btnLabel,
                        style = TextStyle(
                            color = btnTextColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = btnTime,
                        style = TextStyle(
                            color = btnTextColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        ),
                        maxLines = 1
                    )
                }
            }
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
