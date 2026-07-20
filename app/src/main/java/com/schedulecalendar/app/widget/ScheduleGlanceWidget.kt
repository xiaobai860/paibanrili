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
import com.schedulecalendar.app.domain.model.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.LocalDateTime
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.schedulecalendar.app.data.repository.ScheduleRepository
import com.schedulecalendar.app.data.repository.ShiftRepository
import com.schedulecalendar.app.data.repository.ShiftStatusRepository

// ── 快捷打卡小组件数据模型 ──────────────────────────────────────────

data class ClockInWidgetData(
    val shiftName: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val tomorrowShiftName: String = "",
    val actualStartTime: String = "",
    val actualEndTime: String = "",
    val shiftColor: String = "#059669",
    val statusName: String = "",
    // ── 新增：打卡按钮规则控制字段 ──
    val shiftId: String = "",
    val isBuiltInShift: Boolean = false,
    val appliedStatusId: String = "",
    val isBuiltInStatus: Boolean = false,
    val showClockIn: Boolean = false,
    val showClockOut: Boolean = false,
    val hasClockIn: Boolean = false,
    val hasClockOut: Boolean = false,
    val clockInDate: String = "",
    val widgetClockInTime: String = "",
    val widgetClockOutTime: String = ""
)

// ── Glance 状态键 ──────────────────────────────────────────────────

internal val KEY_CLOCK_IN_WIDGET = stringPreferencesKey("clock_in_widget_data")

private const val CLOCK_IN_PREFS = "clock_in_widget_prefs"
private const val KEY_CLOCK_IN_DATE = "clock_in_date"
private const val KEY_CLOCK_IN_TIME = "clock_in_time"
private const val KEY_CLOCK_OUT_TIME = "clock_out_time"
private const val WIDGET_DATA_PREFS = "widget_action_data_prefs"
private const val KEY_WIDGET_JSON = "widget_json"

// ── 快捷打卡 Glance 小组件 ─────────────────────────────────────────

class ScheduleGlanceWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { ClockInWidgetContent() }
    }

    companion object {
        suspend fun updateWidgetData(context: Context, data: ClockInWidgetData) {
            val gson = Gson()
            // 同步保存到 SharedPreferences，供 ActionCallback 读取
            context.getSharedPreferences(WIDGET_DATA_PREFS, Context.MODE_PRIVATE)
                .edit { putString(KEY_WIDGET_JSON, gson.toJson(data)) }
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

// ── Widget 上班打卡动作 ──────────────────────────────────────────

class WidgetClockInAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val now = LocalTime.now()
        val currentTime = "%02d:%02d".format(now.hour, now.minute)

        // 读取 widget 数据
        val prefsJson = context.getSharedPreferences(WIDGET_DATA_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WIDGET_JSON, "") ?: ""
        val data = runCatching { Gson().fromJson(prefsJson, ClockInWidgetData::class.java) }
            .getOrElse { ClockInWidgetData() }

        if (data.clockInDate.isBlank()) {
            fallbackClock(context, glanceId, true)
            return
        }

        val targetDate = data.clockInDate
        val isBuiltInShift = data.isBuiltInShift
        val hasCustomStatus = data.appliedStatusId.isNotBlank() && !data.isBuiltInStatus
        val hasBuiltInStatus = data.appliedStatusId.isNotBlank() && data.isBuiltInStatus

        // 先更新 SharedPreferences（widget 即时刷新）
        val clockPrefs = context.getSharedPreferences(CLOCK_IN_PREFS, Context.MODE_PRIVATE)
        clockPrefs.edit {
            putString(KEY_CLOCK_IN_DATE, targetDate)
            putString(KEY_CLOCK_IN_TIME, currentTime)
            remove(KEY_CLOCK_OUT_TIME)
        }

        // 再持久化到数据库
        runCatching {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext, WidgetClockEntryPoint::class.java
            )
            val scheduleRepo = entryPoint.scheduleRepository()
            val shiftRepo = entryPoint.shiftRepository()

            var record = scheduleRepo.getByDate(targetDate) ?: ScheduleRecord(targetDate)

            if (isBuiltInShift && hasCustomStatus) {
                // 规则4：内置班次+自定义附加状态 → 打卡时间写入附加状态.startTime
                val newStatus = record.appliedStatus?.copy(startTime = currentTime)
                    ?: AppliedStatus(data.appliedStatusId, startTime = currentTime)
                record = record.copy(appliedStatus = newStatus)
            } else {
                // 规则1/2/3：普通班次 → 写入实际上班时间
                record = record.copy(actualStartTime = currentTime)

                if (hasBuiltInStatus && record.shiftId != null) {
                    // 规则3：迟到时段自动填入附加状态
                    val shift = shiftRepo.getById(record.shiftId)
                    if (shift != null && shift.startTime.isNotEmpty()) {
                        val shiftStartMin = CalcUtils.timeToMin(shift.startTime)
                        val actualMin = CalcUtils.timeToMin(currentTime)
                        if (actualMin > shiftStartMin) {
                            val lateEnd = currentTime
                            val newStatus = record.appliedStatus?.copy(
                                startTime = shift.startTime, endTime = lateEnd
                            ) ?: AppliedStatus(
                                data.appliedStatusId, startTime = shift.startTime, endTime = lateEnd
                            )
                            record = record.copy(appliedStatus = newStatus)
                        }
                    }
                }
            }

            scheduleRepo.save(record)
        }

        // 刷新小组件
        refreshWidgets(context, glanceId)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "\u5df2\u6253\u4e0a\u73ed\u5361 $currentTime", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun fallbackClock(context: Context, glanceId: GlanceId, isClockIn: Boolean) {
        val prefs = context.getSharedPreferences(CLOCK_IN_PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now()
        val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)
        val savedDate = prefs.getString(KEY_CLOCK_IN_DATE, "") ?: ""
        val now = LocalTime.now()
        val currentTime = "%02d:%02d".format(now.hour, now.minute)

        prefs.edit {
            if (savedDate != todayStr) {
                putString(KEY_CLOCK_IN_DATE, todayStr)
                putString(KEY_CLOCK_IN_TIME, currentTime)
                remove(KEY_CLOCK_OUT_TIME)
            }
        }
        refreshWidgets(context, glanceId)
        Toast.makeText(context, "\u5df2\u6253\u5361 $currentTime", Toast.LENGTH_SHORT).show()
    }
}

// ── Widget 下班打卡动作 ──────────────────────────────────────────

class WidgetClockOutAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val now = LocalTime.now()
        val currentTime = "%02d:%02d".format(now.hour, now.minute)

        // 读取 widget 数据
        val prefsJson = context.getSharedPreferences(WIDGET_DATA_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WIDGET_JSON, "") ?: ""
        val data = runCatching { Gson().fromJson(prefsJson, ClockInWidgetData::class.java) }
            .getOrElse { ClockInWidgetData() }

        if (data.clockInDate.isBlank()) {
            fallbackClock(context, glanceId, false)
            return
        }

        val targetDate = data.clockInDate
        val isBuiltInShift = data.isBuiltInShift
        val hasCustomStatus = data.appliedStatusId.isNotBlank() && !data.isBuiltInStatus
        val hasBuiltInStatus = data.appliedStatusId.isNotBlank() && data.isBuiltInStatus

        // 先更新 SharedPreferences
        val clockPrefs = context.getSharedPreferences(CLOCK_IN_PREFS, Context.MODE_PRIVATE)
        clockPrefs.edit { putString(KEY_CLOCK_OUT_TIME, currentTime) }

        // 再持久化到数据库
        runCatching {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext, WidgetClockEntryPoint::class.java
            )
            val scheduleRepo = entryPoint.scheduleRepository()
            val shiftRepo = entryPoint.shiftRepository()

            var record = scheduleRepo.getByDate(targetDate) ?: ScheduleRecord(targetDate)

            if (isBuiltInShift && hasCustomStatus) {
                // 规则4：内置班次+自定义附加状态 → 写入附加状态.endTime
                val newStatus = record.appliedStatus?.copy(endTime = currentTime)
                    ?: AppliedStatus(data.appliedStatusId, endTime = currentTime)
                record = record.copy(appliedStatus = newStatus)
            } else {
                // 规则1/2/3：写入实际下班时间
                record = record.copy(actualEndTime = currentTime)

                if (hasBuiltInStatus && record.shiftId != null) {
                    // 规则3：早退时段自动填入附加状态
                    val shift = shiftRepo.getById(record.shiftId)
                    if (shift != null && shift.endTime.isNotEmpty()) {
                        val (_, normSE) = CalcUtils.normRange(
                            CalcUtils.timeToMin(shift.startTime),
                            CalcUtils.timeToMin(shift.endTime)
                        )
                        val (_, normAE) = CalcUtils.normRange(
                            CalcUtils.timeToMin(shift.startTime),
                            CalcUtils.timeToMin(currentTime)
                        )
                        if (normSE - normAE > 0) {
                            val earlyStart = currentTime
                            val newStatus = record.appliedStatus?.copy(
                                startTime = earlyStart, endTime = shift.endTime
                            ) ?: AppliedStatus(
                                data.appliedStatusId, startTime = earlyStart, endTime = shift.endTime
                            )
                            record = record.copy(appliedStatus = newStatus)
                        }
                    }
                }
            }

            scheduleRepo.save(record)
        }

        // 刷新小组件
        refreshWidgets(context, glanceId)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "\u5df2\u6253\u4e0b\u73ed\u5361 $currentTime", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun fallbackClock(context: Context, glanceId: GlanceId, isClockIn: Boolean) {
        val prefs = context.getSharedPreferences(CLOCK_IN_PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now()
        val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)
        val savedDate = prefs.getString(KEY_CLOCK_IN_DATE, "") ?: ""
        val now = LocalTime.now()
        val currentTime = "%02d:%02d".format(now.hour, now.minute)

        val clockInTime = prefs.getString(KEY_CLOCK_IN_TIME, "") ?: ""
        if (savedDate == todayStr && clockInTime.isNotEmpty()) {
            prefs.edit { putString(KEY_CLOCK_OUT_TIME, currentTime) }
        }
        refreshWidgets(context, glanceId)
        Toast.makeText(context, "\u5df2\u6253\u5361 $currentTime", Toast.LENGTH_SHORT).show()
    }
}

/** 刷新两个小组件 */
private suspend fun refreshWidgets(context: Context, glanceId: GlanceId) {
    val widget = ScheduleGlanceWidget()
    widget.update(context, glanceId)
    CalendarGlanceWidget().let { w ->
        GlanceAppWidgetManager(context).getGlanceIds(w.javaClass).forEach { w.update(context, it) }
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
    // 使用 data.clockInDate 作为参考日期（支持跨午夜场景）
    val refDate = data.clockInDate.ifBlank { todayStr }
    val savedDate = clockPrefs.getString(KEY_CLOCK_IN_DATE, "") ?: ""
    val actualStart = if (savedDate == refDate) clockPrefs.getString(KEY_CLOCK_IN_TIME, "") ?: "" else ""
    val actualEnd = if (savedDate == refDate) clockPrefs.getString(KEY_CLOCK_OUT_TIME, "") ?: "" else ""

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

        // ── 右侧：打卡按钮（使用 showClockIn/showClockOut 规则控制） ──
        val showBtn = data.showClockIn || data.showClockOut
        if (showBtn) {
            val btnLabel: String
            val btnTime: String
            val btnBgColor: ColorProvider
            val btnTextColor: ColorProvider
            val btnAction: ActionCallback
            when {
                // 上班打卡按钮
                data.showClockIn && !data.hasClockIn -> {
                    btnLabel = "\u4e0a\u73ed\u5361"
                    btnTime = ""
                    btnBgColor = pickColor(Color(0xFF059669).copy(alpha = 0.12f * bgAlpha), Color(0xFF059669).copy(alpha = 0.22f), isDark)
                    btnTextColor = pickColor(Color(0xFF059669), Color(0xFF4ADE80), isDark)
                    btnAction = WidgetClockInAction()
                }
                // 下班打卡按钮（已上班未下班）
                data.showClockOut && data.hasClockIn && !data.hasClockOut -> {
                    btnLabel = "\u4e0b\u73ed\u5361"
                    btnTime = actualStart.take(5)
                    btnBgColor = pickColor(Color(0xFFF59E0B).copy(alpha = 0.12f * bgAlpha), Color(0xFFF59E0B).copy(alpha = 0.22f), isDark)
                    btnTextColor = pickColor(Color(0xFFD97706), Color(0xFFFBBF24), isDark)
                    btnAction = WidgetClockOutAction()
                }
                // 已全部打卡 → 灰色不可点击状态
                else -> {
                    btnLabel = "\u4e0b\u73ed\u5361"
                    btnTime = actualEnd.take(5)
                    btnBgColor = pickColor(Color(0xFF9CA3AF).copy(alpha = 0.10f * bgAlpha), Color(0xFF9CA3AF).copy(alpha = 0.18f), isDark)
                    btnTextColor = pickColor(Color(0xFF6B7280), Color(0xFF9CA3AF), isDark)
                    btnAction = WidgetClockOutAction()
                }
            }

            Box(
                modifier = GlanceModifier
                    .width(40.dp)
                    .height(36.dp)
                    .background(btnBgColor)
                    .cornerRadius(10.dp)
                    .clickable(actionRunCallback(btnAction::class.java)),
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

// ── 判断附加状态是否为内置（调休/请假） ──────────────────────────────

fun isBuiltInStatus(statusId: String): Boolean {
    return statusId == BUILTIN_STATUS_LEAVE || statusId == BUILTIN_STATUS_SWAP
}

// ── Hilt EntryPoint：允许小组件 ActionCallback 访问 Repository ─────

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetClockEntryPoint {
    fun scheduleRepository(): ScheduleRepository
    fun shiftRepository(): ShiftRepository
    fun shiftStatusRepository(): ShiftStatusRepository
}
