// app/src/main/java/com/schedulecalendar/app/widget/ScheduleGlanceReceiver.kt
package com.schedulecalendar.app.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Glance 小组件 BroadcastReceiver
 * 替代旧的 AppWidgetProvider，完全 Compose-first
 */
class ScheduleGlanceReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScheduleGlanceWidget()
}
