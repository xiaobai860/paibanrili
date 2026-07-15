// app/src/main/java/com/schedulecalendar/app/widget/CalendarGlanceReceiver.kt
package com.schedulecalendar.app.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 日历网格 Glance 小组件 BroadcastReceiver
 * 3x3 桌面日历小组件，显示当月日期 + 班次 + 附加状态
 */
class CalendarGlanceReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CalendarGlanceWidget()
}
