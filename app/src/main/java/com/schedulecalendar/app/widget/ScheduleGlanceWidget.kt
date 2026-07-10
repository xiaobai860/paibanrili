// app/src/main/java/com/schedulecalendar/app/widget/ScheduleGlanceWidget.kt
package com.schedulecalendar.app.widget

import android.content.Context
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

/** 创建 ColorProvider 的辅助函数，绕过库组限制 */
private fun cp(color: Color): ColorProvider = object : ColorProvider {
    override fun getColor(context: Context): Color = color
}

/** Glance 小组件状态键 */
internal val KEY_WIDGET_JSON = stringPreferencesKey("glance_widget_data")

/** Glance 小组件数据 */
data class GlanceWidgetData(
    val todayShift: String      = "",
    val todayShiftColor: String = "#059669",
    val workDays: Int           = 0,
    val restDays: Int           = 0,
    val breakdown: String       = ""
)

/**
 * 排班日历 Jetpack Glance 小组件
 * ─ 完全 Compose-first，无 XML 布局，无 RemoteViews
 */
class ScheduleGlanceWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceWidgetContent() }
    }

    companion object {
        /** 由 CalendarViewModel 调用，更新所有小组件状态后触发刷新 */
        suspend fun updateWidgetData(context: Context, data: GlanceWidgetData) {
            val gson   = Gson()
            val widget = ScheduleGlanceWidget()
            val manager = GlanceAppWidgetManager(context)
            manager.getGlanceIds(ScheduleGlanceWidget::class.java).forEach { glanceId ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                    prefs.toMutablePreferences().also { it[KEY_WIDGET_JSON] = gson.toJson(data) }
                }
                widget.update(context, glanceId)
            }
        }
    }
}

@Composable
private fun GlanceWidgetContent() {
    val prefs    = currentState<androidx.datastore.preferences.core.Preferences>()
    val jsonStr  = prefs[KEY_WIDGET_JSON]
    val data     = if (!jsonStr.isNullOrBlank())
        runCatching { Gson().fromJson(jsonStr, GlanceWidgetData::class.java) }.getOrElse { GlanceWidgetData() }
    else GlanceWidgetData()

    val today    = LocalDate.now()
    val dateText = "${today.monthValue}月${today.dayOfMonth}日"

    val bgColor = runCatching {
        val hex = data.todayShiftColor
        val r   = Integer.parseInt(hex.substring(1, 3), 16)
        val g   = Integer.parseInt(hex.substring(3, 5), 16)
        val b   = Integer.parseInt(hex.substring(5, 7), 16)
        Color(r / 255f, g / 255f, b / 255f, 1f)
    }.getOrElse { Color(0xFF059669) }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(cp(bgColor))
            .padding(12.dp)
            .clickable(actionRunCallback<OpenAppAction>()),
        contentAlignment = Alignment.TopStart
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {

            // ─── 顶部行：日期 + 班次名 ───────────────────────────
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text  = dateText,
                    style = TextStyle(
                        color      = cp(Color.White),
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
                Box(
                    modifier = GlanceModifier
                        .background(cp(Color.White.copy(alpha = 0.20f)))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text  = data.todayShift.ifEmpty { "暂无排班" },
                        style = TextStyle(
                            color      = cp(Color.White),
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(8.dp))

            // ─── 分隔线 ──────────────────────────────────────────
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(cp(Color.White.copy(alpha = 0.25f)))
            ) {}

            Spacer(modifier = GlanceModifier.height(8.dp))

            // ─── 底部统计行 ────────────────────────────────────────
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 工作天
                StatColumn(value = data.workDays.toString(), label = "工作天")

                // 竖线分隔
                Box(
                    modifier = GlanceModifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(cp(Color.White.copy(alpha = 0.25f)))
                ) {}

                // 休息天
                StatColumn(value = data.restDays.toString(), label = "休息天")

                // 竖线分隔
                Box(
                    modifier = GlanceModifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(cp(Color.White.copy(alpha = 0.25f)))
                ) {}

                // 班次明细
                Column(
                    modifier          = GlanceModifier.defaultWeight().padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text  = data.breakdown.ifEmpty { "本月暂无排班" },
                        style = TextStyle(
                            color    = cp(Color.White.copy(alpha = 0.85f)),
                            fontSize = 10.sp
                        ),
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun StatColumn(value: String, label: String) {
    Column(
        modifier          = GlanceModifier.width(52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment   = Alignment.CenterVertically
    ) {
        Text(
            text  = value,
            style = TextStyle(
                color      = cp(Color.White),
                fontSize   = 20.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Text(
            text  = label,
            style = TextStyle(
                color    = cp(Color.White.copy(alpha = 0.80f)),
                fontSize = 10.sp
            )
        )
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
            ?.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        intent?.let { context.startActivity(it) }
    }
}
