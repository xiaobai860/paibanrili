// app/src/main/java/com/schedulecalendar/app/widget/WidgetRefreshWorker.kt
package com.schedulecalendar.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 小组件周期刷新兜底 Worker。
 *
 * 设计原则（对齐硬约束 #2）：
 * - 真正的「数据变了才更新」由 App 的 ViewModel（CalendarViewModel.syncWidget / syncCalendarWidget）
 *   事件驱动完成，并调用各 Widget 的 updateXxx 方法写入最新 SharedPreferences 数据。
 * - 本 Worker 仅做「兜底」：每 15 分钟枚举三类小组件的所有实例，触发一次重渲染（从已有
 *   SharedPreferences 重新加载），避免系统杀进程 / 重启后桌面组件长期显示陈旧内容，
 *   同时保证「今日高亮 / 明天班次 / 节假日倒计时」等日期相关 UI 随日期推进而刷新。
 * - 不在 Worker 内做重数据拉取（重活交给事件驱动的 ViewModel），本 Worker 只重渲染，
 *   轻量且不会与事件驱动路径冲突。
 */
class WidgetRefreshWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val manager = GlanceAppWidgetManager(appContext)
            val widgets: List<GlanceAppWidget> = listOf(
                ScheduleGlanceWidget(),
                CalendarGlanceWidget(),
                Calendar3x4GlanceWidget()
            )
            widgets.forEach { widget ->
                manager.getGlanceIds(widget.javaClass).forEach { glanceId ->
                    widget.update(appContext, glanceId)
                }
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }
}
