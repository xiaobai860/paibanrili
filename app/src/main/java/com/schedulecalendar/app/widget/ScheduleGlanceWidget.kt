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
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.google.gson.Gson
import com.schedulecalendar.app.data.prefs.AppPreferences
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ── 打开小组件样式配置页 ──────────────────────────────────────────
class OpenWidgetConfigAction : ActionCallback {
    companion object { val KEY_TYPE = ActionParameters.Key<String>("widget_type") }
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val type = parameters[KEY_TYPE] ?: return
        val intent = Intent(context, WidgetConfigActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("widget_type", type)
        }
        context.startActivity(intent)
    }
}

/** 2x1 打卡组件手动刷新：回源数据库重新计算并写入数据 */
class RefreshScheduleWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val ok = syncAllWidgets(context)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, if (ok) "刷新成功" else "刷新失败", Toast.LENGTH_SHORT).show()
        }
    }
}

// ── 快捷打卡小组件数据模型 ──────────────────────────────────────────

data class ClockInWidgetData(
    val shiftName: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val tomorrowShiftName: String = "",
    val tomorrowStatusName: String = "",
    val actualStartTime: String = "",
    val actualEndTime: String = "",
    val shiftColor: String = "#059669",
    val statusName: String = "",
    // ── 附加状态时间段（内置班次+附加状态场景的打卡与显示）──
    val statusStartTime: String = "",
    val statusEndTime: String = "",
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
    val widgetClockOutTime: String = "",
    /** S1：休息/调休且无附加状态时第二行显示的文案 */
    val restMessage: String = "",
    /** 2x1 第三行：跨天班次提醒文案（夜班提醒规则，含「明天：xx」兜底格式）；空 = 不显示 */
    val nextShiftFooter: String = ""
)

// ── 存储键 ──────────────────────────────────────────────────

private const val WIDGET_DATA_PREFS = "widget_action_data_prefs"
private const val KEY_WIDGET_JSON = "widget_json"

// ── 快捷打卡 Glance 小组件 ─────────────────────────────────────────

class ScheduleGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { ClockInWidgetContent() }
    }

    companion object {
        suspend fun updateWidgetData(context: Context, data: ClockInWidgetData) {
            val gson = Gson()
            // 打卡时间真值统一来自数据库（由 CalendarViewModel.syncWidget 计算并写入 data），
            // 不再使用独立的 clock_in_widget_prefs 存储，避免两套机制数据不一致。
            // 保存到 SharedPreferences，供 content 与 ActionCallback 读取
            context.getSharedPreferences(WIDGET_DATA_PREFS, Context.MODE_PRIVATE)
                .edit { putString(KEY_WIDGET_JSON, gson.toJson(data)) }
            val widget = ScheduleGlanceWidget()
            val manager = GlanceAppWidgetManager(context)
            manager.getGlanceIds(ScheduleGlanceWidget::class.java).forEach { glanceId ->
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
        android.util.Log.d("WIDGET_DBG", "clockIn start date=${data.clockInDate} jsonLen=${prefsJson.length}")

        if (data.clockInDate.isBlank()) {
            android.util.Log.d("WIDGET_DBG", "clockIn fallback path")
            fallbackClock(context, glanceId, true)
            return
        }

        val targetDate = data.clockInDate
        val isBuiltInShift = data.isBuiltInShift
        val hasCustomStatus = data.appliedStatusId.isNotBlank() && !data.isBuiltInStatus
        val hasBuiltInStatus = data.appliedStatusId.isNotBlank() && data.isBuiltInStatus

        // 持久化到数据库（widget 数据真值统一来自 ScheduleRecord）
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext, WidgetClockEntryPoint::class.java
            )
            val scheduleRepo = entryPoint.scheduleRepository()
            val shiftRepo = entryPoint.shiftRepository()

            var record = scheduleRepo.getByDate(targetDate) ?: ScheduleRecord(targetDate)

            if (isBuiltInShift && (hasCustomStatus || hasBuiltInStatus)) {
                // S3：休息/调休 + 附加状态 → 写入附加状态开始时间（需求 §3.3）
                val newStatus = record.appliedStatus?.copy(startTime = currentTime)
                    ?: AppliedStatus(data.appliedStatusId, startTime = currentTime)
                record = record.copy(appliedStatus = newStatus)
            } else if (!isBuiltInShift && hasBuiltInStatus) {
                // S4：正常班 + 请假/调休 → 写实际上班时间 + 重算附加状态时间段（需求 §3.5）
                record = record.copy(actualStartTime = currentTime)
                val shift = record.shiftId?.let { id -> shiftRepo.getById(id) }
                if (shift != null) {
                    val grain = entryPoint.appPreferences().attendConfigFlow.first().overtimeGranMin
                    record = applyS4StatusRange(record, shift, grain)
                }
            } else {
                // S2 / S5（按 S2 处理）：写实际上班时间
                record = record.copy(actualStartTime = currentTime)
            }

            scheduleRepo.save(record)
            android.util.Log.d("WIDGET_DBG", "clockIn saved target=$targetDate time=$currentTime")
        } catch (e: Exception) {
            android.util.Log.e("WIDGET_DBG", "clockIn save failed", e)
        }

        // 刷新小组件：数据落库后由 ScheduleApp 全局变更信号自动触发同步（避免动作内多次 update 被桌面丢弃）
        android.util.Log.d("WIDGET_DBG", "clockIn posting toast")
        Handler(Looper.getMainLooper()).postDelayed({
            Toast.makeText(context.applicationContext, "已打上班卡 $currentTime", Toast.LENGTH_SHORT).show()
        }, 500)
        // 兜底：信号同步可能落在桌面「交互窗口期」被丢弃，延迟补一次单路更新
        widgetFallbackScope.launch {
            delay(1200)
            runCatching { syncAllWidgets(context.applicationContext) }
        }
    }

    private suspend fun fallbackClock(context: Context, glanceId: GlanceId, isClockIn: Boolean) {
        val today = LocalDate.now()
        val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)
        val now = LocalTime.now()
        val currentTime = "%02d:%02d".format(now.hour, now.minute)

        // 无 widget 数据兜底：直接写入当天数据库记录
        runCatching {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext, WidgetClockEntryPoint::class.java
            )
            val scheduleRepo = entryPoint.scheduleRepository()
            val record = scheduleRepo.getByDate(todayStr) ?: ScheduleRecord(todayStr)
            val updated = if (isClockIn) record.copy(actualStartTime = currentTime)
                else record.copy(actualEndTime = currentTime)
            scheduleRepo.save(updated)
        }
        // 全局变更信号会自动触发同步
        Toast.makeText(context, "已打卡 $currentTime", Toast.LENGTH_SHORT).show()
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

        // 持久化到数据库（widget 数据真值统一来自 ScheduleRecord）
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext, WidgetClockEntryPoint::class.java
            )
            val scheduleRepo = entryPoint.scheduleRepository()
            val shiftRepo = entryPoint.shiftRepository()

            var record = scheduleRepo.getByDate(targetDate) ?: ScheduleRecord(targetDate)

            if (isBuiltInShift && (hasCustomStatus || hasBuiltInStatus)) {
                // S3：休息/调休 + 附加状态 → 写入附加状态结束时间（需求 §3.3，支持跨天加班）
                val newStatus = record.appliedStatus?.copy(endTime = currentTime)
                    ?: AppliedStatus(data.appliedStatusId, endTime = currentTime)
                record = record.copy(appliedStatus = newStatus)
            } else if (!isBuiltInShift && hasBuiltInStatus) {
                // S4：正常班 + 请假/调休 → 写实际下班时间 + 重算附加状态时间段（需求 §3.5）
                record = record.copy(actualEndTime = currentTime)
                val shift = record.shiftId?.let { id -> shiftRepo.getById(id) }
                if (shift != null) {
                    val grain = entryPoint.appPreferences().attendConfigFlow.first().overtimeGranMin
                    record = applyS4StatusRange(record, shift, grain)
                }
            } else {
                // S2 / S5（按 S2 处理）：写实际下班时间（允许未打上班卡直接打下班卡）
                record = record.copy(actualEndTime = currentTime)
            }

            scheduleRepo.save(record)
            android.util.Log.d("WIDGET_DBG", "clockOut saved target=$targetDate time=$currentTime")
        } catch (e: Exception) {
            android.util.Log.e("WIDGET_DBG", "clockOut save failed", e)
        }

        // 刷新小组件：数据落库后由 ScheduleApp 全局变更信号自动触发同步
        android.util.Log.d("WIDGET_DBG", "clockOut posting toast")
        Handler(Looper.getMainLooper()).postDelayed({
            Toast.makeText(context.applicationContext, "已打下班卡 $currentTime", Toast.LENGTH_SHORT).show()
        }, 500)
        // 兜底：信号同步可能落在桌面「交互窗口期」被丢弃，延迟补一次单路更新
        widgetFallbackScope.launch {
            delay(1200)
            runCatching { syncAllWidgets(context.applicationContext) }
        }
    }

    private suspend fun fallbackClock(context: Context, glanceId: GlanceId, isClockIn: Boolean) {
        val today = LocalDate.now()
        val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)
        val now = LocalTime.now()
        val currentTime = "%02d:%02d".format(now.hour, now.minute)

        // 无 widget 数据兜底：直接写入当天数据库记录
        runCatching {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext, WidgetClockEntryPoint::class.java
            )
            val scheduleRepo = entryPoint.scheduleRepository()
            val record = scheduleRepo.getByDate(todayStr) ?: ScheduleRecord(todayStr)
            val updated = if (isClockIn) record.copy(actualStartTime = currentTime)
                else record.copy(actualEndTime = currentTime)
            scheduleRepo.save(updated)
        }
        // 全局变更信号会自动触发同步
        Toast.makeText(context, "已打卡 $currentTime", Toast.LENGTH_SHORT).show()
    }
}

// ── 小组件 UI 内容 ─────────────────────────────────────────────────

/** 将十六进制颜色字符串转为 androidx.compose.ui.graphics.Color（含深色模式适配） */
private fun parseShiftColor(hex: String, isDark: Boolean): Color {
    val h = hex.removePrefix("#")
    val lightColor = if (h.length >= 6) {
        val r = h.substring(0, 2).toIntOrNull(16) ?: 0x05
        val g = h.substring(2, 4).toIntOrNull(16) ?: 0x96
        val b = h.substring(4, 6).toIntOrNull(16) ?: 0x69
        Color(r / 255f, g / 255f, b / 255f, 1f)
    } else Color(0xFF059669)
    return if (isDark) lightColor.copy(alpha = 0.85f) else lightColor
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
            "今天${name}就是！" else "今日停工"
    }

    // 查找下一个最近法定节假日首日
    val (holidayName, daysUntil) = HolidayData.getNextHolidayCountdown(todayStr)
    return if (daysUntil > 0) {
        "距${holidayName}还有${daysUntil}天"
    } else if (daysUntil == 0) {
        "今天${holidayName}就是！"
    } else {
        ""
    }
}

@Suppress("LocalContextConfigurationRead")
@Composable
private fun ClockInWidgetContent() {
    val prefs = androidx.glance.LocalContext.current.getSharedPreferences(WIDGET_DATA_PREFS, Context.MODE_PRIVATE)
    val jsonStr = prefs.getString(KEY_WIDGET_JSON, "")
    val data = if (!jsonStr.isNullOrBlank())
        runCatching { Gson().fromJson(jsonStr, ClockInWidgetData::class.java) }
            .getOrElse { ClockInWidgetData() }
    else ClockInWidgetData()

    val context = LocalContext.current

    // 打卡时间统一来自数据库（syncWidget 写入 data.actualStartTime/actualEndTime）
    val actualStart = data.actualStartTime
    val actualEnd = data.actualEndTime

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

    // 内置班次（休息/调休）+ 附加状态（内置或自定义）：打卡与显示均以附加状态时间段为准
    val isBuiltInWithStatus = data.isBuiltInShift && data.appliedStatusId.isNotBlank()
    val statusHasTime = data.statusStartTime.isNotEmpty() || data.statusEndTime.isNotEmpty()
    val statusTimeText = when {
        data.statusStartTime.isNotEmpty() && data.statusEndTime.isNotEmpty() ->
            "${data.statusStartTime}–${data.statusEndTime}"
        data.statusStartTime.isNotEmpty() -> data.statusStartTime
        data.statusEndTime.isNotEmpty() -> data.statusEndTime
        else -> "未设置时间段"
    }
    val timeText = if (isBuiltInWithStatus) statusTimeText else "$displayStart–$displayEnd"
    val hasTimeContent = isBuiltInWithStatus || data.startTime.isNotEmpty() || data.endTime.isNotEmpty()

    // === 新外观 2x1 V4 (返回 3 行 defaultWeight 等分高度布局) ===

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(ubg))
            .cornerRadius(12.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            // 第一行：班次徽章 + 附加状态名 + 齿轮（设置入口）
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = GlanceModifier
                        .background(ColorProvider(shiftColor.copy(alpha = 0.18f * bgAlpha)))
                        .cornerRadius(4.dp)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                        .clickable(actionRunCallback<OpenAppAction>())
                ) {
                    Text(
                        text = if (data.shiftName.isNotEmpty()) data.shiftName else "今日无排班",
                        style = if (data.shiftName.isNotEmpty())
                            TextStyle(
                                color = if (isDark) ColorProvider(shiftColor.copy(alpha = 0.95f)) else ColorProvider(shiftColor),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        else
                            TextStyle(
                                color = pickColor(utc.copy(alpha = 0.55f), utcDark.copy(alpha = 0.55f), isDark),
                                fontSize = 12.sp
                            ),
                        maxLines = 1
                    )
                }
                if (data.statusName.isNotEmpty()) {
                    Text(
                        text = "\u00b7 ${data.statusName}",
                        style = TextStyle(
                            color = pickColor(Color(0xFFF97316), Color(0xFFFB923C), isDark),
                            fontSize = 12.sp
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.padding(start = 4.dp).defaultWeight()
                    )
                } else {
                    Spacer(modifier = GlanceModifier.defaultWeight())
                }
                Spacer(modifier = GlanceModifier.width(4.dp))
                widgetIcon(
                    context = context,
                    resId = com.schedulecalendar.app.R.drawable.ic_widget_gear,
                    color = if (isDark) utcDark.copy(alpha = 0.4f) else utc.copy(alpha = 0.4f),
                    modifier = GlanceModifier
                        .size(18.dp)
                        .clickable(
                            actionRunCallback<OpenWidgetConfigAction>(
                                parameters = actionParametersOf(OpenWidgetConfigAction.KEY_TYPE to WIDGET_TYPE_SCHEDULE)
                            )
                        )
                )
                Spacer(modifier = GlanceModifier.width(2.dp))
                widgetIcon(
                    context = context,
                    resId = com.schedulecalendar.app.R.drawable.ic_widget_refresh,
                    color = if (isDark) utcDark.copy(alpha = 0.4f) else utc.copy(alpha = 0.4f),
                    modifier = GlanceModifier
                        .size(18.dp)
                        .clickable(actionRunCallback<RefreshScheduleWidgetAction>())
                )
            }

            // 第二行：大号时间 + 打卡按钮（S1 显示休息文案）
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (data.restMessage.isNotEmpty()) {
                    // S1：休息/调休且无附加状态 → 显示文案，无按钮（需求规则1）
                    Text(
                        text = data.restMessage,
                        style = TextStyle(
                            color = pickColor(utc.copy(alpha = 0.6f), utcDark.copy(alpha = 0.6f), isDark),
                            fontSize = 13.sp
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                } else if (hasTimeContent) {
                    val timeColor = when {
                        isBuiltInWithStatus -> when {
                            data.statusEndTime.isNotEmpty() -> pickColor(Color(0xFF10B981), Color(0xFF4ADE80), isDark)
                            data.statusStartTime.isNotEmpty() -> pickColor(Color(0xFFF59E0B), Color(0xFFFBBF24), isDark)
                            else -> pickColor(utc.copy(alpha = 0.6f), utcDark.copy(alpha = 0.6f), isDark)
                        }
                        hasClockIn && !hasClockOut -> pickColor(Color(0xFFF59E0B), Color(0xFFFBBF24), isDark)
                        hasClockOut -> pickColor(Color(0xFF10B981), Color(0xFF4ADE80), isDark)
                        else -> pickColor(utc, utcDark, isDark)
                    }
                    Text(
                        text = timeText,
                        style = TextStyle(color = timeColor, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                } else {
                    Spacer(modifier = GlanceModifier.defaultWeight())
                }
                val showBtn = data.showClockIn || data.showClockOut
                if (showBtn) {
                    // 内置班次+附加状态：以附加状态时间作为打卡进度；普通班次用实际打卡时间
                    val btnInDone = if (isBuiltInWithStatus) data.statusStartTime.isNotEmpty() else data.hasClockIn
                    val btnOutDone = if (isBuiltInWithStatus) data.statusEndTime.isNotEmpty() else data.hasClockOut
                    val btnLabel: String
                    val btnBgColor: ColorProvider
                    val btnTextColor: ColorProvider
                    val btnAction: ActionCallback
                    when {
                        data.showClockIn && !btnInDone -> {
                            btnLabel = "上班卡"
                            btnBgColor = pickColor(Color(0xFF059669).copy(alpha = 0.18f * bgAlpha), Color(0xFF059669).copy(alpha = 0.30f), isDark)
                            btnTextColor = pickColor(Color(0xFF059669), Color(0xFF4ADE80), isDark)
                            btnAction = WidgetClockInAction()
                        }
                        data.showClockOut && !btnOutDone -> {
                            // 允许未打上班卡直接打下班卡（决策点6，仅 S2 窗口内会出现）
                            btnLabel = "下班卡"
                            btnBgColor = pickColor(Color(0xFFF59E0B).copy(alpha = 0.18f * bgAlpha), Color(0xFFF59E0B).copy(alpha = 0.30f), isDark)
                            btnTextColor = pickColor(Color(0xFFD97706), Color(0xFFFBBF24), isDark)
                            btnAction = WidgetClockOutAction()
                        }
                        else -> {
                            btnLabel = "下班卡"
                            btnBgColor = pickColor(Color(0xFF9CA3AF).copy(alpha = 0.14f * bgAlpha), Color(0xFF9CA3AF).copy(alpha = 0.22f), isDark)
                            btnTextColor = pickColor(Color(0xFF6B7280), Color(0xFF9CA3AF), isDark)
                            btnAction = WidgetClockOutAction()
                        }
                    }
                    Box(
                        modifier = GlanceModifier
                            .padding(start = 0.dp)
                            .fillMaxHeight()
                            .background(btnBgColor)
                            .cornerRadius(6.dp)
                            .padding(start = 2.dp, top = 3.dp, end = 2.dp, bottom = 3.dp)
                            .clickable(actionRunCallback(btnAction::class.java)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = btnLabel,
                            style = TextStyle(
                                color = btnTextColor, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1
                        )
                    }
                }
            }

            // 第三行：明天班次（含附加状态）/ 跨天夜班提醒 / 节假日倒计时（字号 12sp）
            // nextShiftFooter 由 WidgetSync 按夜班提醒规则生成（「明天晚上/今天晚上/明天晚班/今天晚班/明天：xx」）；
            // 为空时回退旧格式（兼容旧缓存 JSON 缺字段的情况）
            val footerText = when (displayMode) {
                DISPLAY_MODE_SHIFT_HOLIDAY -> getHolidayCountdownText()
                else -> data.nextShiftFooter.ifEmpty {
                    if (data.tomorrowShiftName.isNotEmpty()) {
                        if (data.tomorrowStatusName.isNotEmpty())
                            "明天：${data.tomorrowShiftName} · ${data.tomorrowStatusName}"
                        else "明天：${data.tomorrowShiftName}"
                    } else ""
                }
            }
            if (footerText.isNotEmpty()) {
                Box(
                    modifier = GlanceModifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = footerText,
                        style = TextStyle(
                            color = if (displayMode == DISPLAY_MODE_SHIFT_HOLIDAY)
                                pickColor(Color(0xFFDC2626), Color(0xFFEF4444), isDark)
                            else
                                pickColor(utc.copy(alpha = 0.6f), utcDark.copy(alpha = 0.6f), isDark),
                            fontSize = 12.sp
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
    fun appPreferences(): AppPreferences
}
