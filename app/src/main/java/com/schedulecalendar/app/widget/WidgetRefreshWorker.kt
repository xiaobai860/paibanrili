// app/src/main/java/com/schedulecalendar/app/widget/WidgetRefreshWorker.kt
package com.schedulecalendar.app.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

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
 */
class WidgetRefreshWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            syncAllWidgets(appContext)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }
}
