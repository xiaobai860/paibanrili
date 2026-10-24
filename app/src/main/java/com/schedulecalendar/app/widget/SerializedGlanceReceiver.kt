// app/src/main/java/com/schedulecalendar/app/widget/SerializedGlanceReceiver.kt
package com.schedulecalendar.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.schedulecalendar.app.ScheduleApp
import kotlinx.coroutines.launch

/**
 * 串行化 Glance 更新的 Receiver 基类。
 *
 * 系统触发的 [onUpdate]（新增组件 / 开机 / 尺寸或配置变更）默认由 [GlanceAppWidgetReceiver]
 * 内部协程直接 update，可能与 APP 内 [syncAllWidgets] 的 update 重叠 → 上一次 provideGlance
 * 组合被取消，Glance 抛 "The coroutine scope left the composition"（非致命，但刷 error 日志）。
 *
 * 这里把系统 onUpdate 也收敛到 [widgetComposeMutex]，与 APP 内 [updateSerialized] 共用同一把锁，
 * 保证任意时刻只有一个组件在组合，从根上消除两类路径的重叠取消。
 *
 * onUpdate 用 goAsync() 延长广播生命周期至协程完成（与 BootReceiver/AlarmReceiver 一致），
 * 协程跑在 [ScheduleApp.appScope]（进程存活期有效，不泄漏到进程外）。
 */
abstract class SerializedGlanceReceiver : GlanceAppWidgetReceiver() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val app = context.applicationContext as? ScheduleApp ?: return super.onUpdate(
            context, appWidgetManager, appWidgetIds
        )
        val pendingResult = goAsync()
        app.appScope.launch {
            try {
                // 该组件全部实例共用一份 SharedPreferences 数据，逐个串行重绘即等价于按 appWidgetIds 更新，
                // 且省去 appWidgetId→GlanceId 映射；统一走 widgetComposeMutex，避免与 APP 内同步的组合重叠。
                val manager = GlanceAppWidgetManager(context)
                manager.getGlanceIds(glanceAppWidget::class.java).forEach { glanceId ->
                    glanceAppWidget.updateSerialized(context, glanceId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
