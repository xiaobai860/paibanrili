// app/src/main/java/com/schedulecalendar/app/widget/CalendarGlance3x4Receiver.kt
package com.schedulecalendar.app.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 3x4 日历网格 Glance 小组件 BroadcastReceiver
 * 3 cell 宽 × 4 cell 高，可拉伸到 4 cell 宽，显示当月日期 + 班次 + 附加状态
 */
class CalendarGlance3x4Receiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = Calendar3x4GlanceWidget()
}