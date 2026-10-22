// app/src/main/java/com/schedulecalendar/app/widget/WidgetRefreshWorker.kt
package com.schedulecalendar.app.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors

/**
 * 小组件周期刷新兜底 Worker（15 分钟）。
 *
 * 职责：从数据库回源重新计算并写入全部小组件数据，使以下「时间敏感」内容随当前时间自动更新，
 * 无需用户打开 App：
 * - 2x1 打卡按钮状态（上班卡/下班卡随班次时间窗口出现/消失，含提前 5 分钟预展示）
 * - 「明天：X」班次信息（零点后自动切换到新的一天）
 * - 「距 X 还有 N 天」节假日倒计时（每日刷新）
 * - 日历组件当天高亮与日期推进
 *
 * 触发链：ScheduleApp.onCreate 注册的 15 分钟周期任务；任何一次运行都会读取数据库最新数据重算，
 * 轻量（3~4 次 Room 查询 + 组件重绘），并由 syncAllWidgets 内部互斥锁串行化，不与事件驱动路径冲突。
 *
 * **自愈重排（关键）**：国产 ROM（如 OPPO/ColorOS）的省电/待机策略会在清理应用进程时
 * **取消该应用的所有 AlarmManager 闹钟**（上下班提醒 + 小组件刷新闹钟），且不设置 force-stop 标记，
 * 导致提醒「莫名不触发」。WorkManager 周期任务由系统托管、可跨进程存活并在重启后自动续期，
 * 因此这里每次运行都顺带 **force 重排**上下班提醒与小组件刷新闹钟，
 * 使被 ROM 清掉的闹钟最多在 15 分钟内自动恢复，无需用户手动打开应用。
 */
class WidgetRefreshWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            // 1) 回源重算并刷新全部小组件
            syncAllWidgets(appContext)

            // 2) 自愈：force 重排上下班提醒闹钟 + 小组件刷新闹钟
            //    （force=true 绕过「签名未变则跳过」，确保被 ROM 取消的闹钟一定被重新注册）
            val ep = EntryPointAccessors.fromApplication(
                appContext.applicationContext, WidgetClockEntryPoint::class.java
            )
            ep.reminderScheduler().scheduleActiveReminders(force = true)
            ep.widgetRefreshScheduler().scheduleWidgetRefreshes()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }
}