// app/src/main/java/com/schedulecalendar/app/widget/WidgetRefreshReceiver.kt
package com.schedulecalendar.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.schedulecalendar.app.ScheduleApp
import com.schedulecalendar.app.domain.util.AppLog
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 小组件精确刷新闹钟接收器（由 [WidgetRefreshScheduler] 注册的 setExactAndAllowWhileIdle 唤醒）。
 *
 * 到点后：回源数据库重算并刷新全部小组件 → 再重排下一批刷新闹钟（形成自维持链）。
 * 使用 goAsync() 延长广播生命周期至协程完成，避免系统在 onReceive 早退时杀进程；
 * 协程跑在 [ScheduleApp.appScope]（进程存活期有效，不泄漏到进程外）。
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetRefreshScheduler.ACTION_WIDGET_REFRESH) return

        val app = context.applicationContext as? ScheduleApp ?: return
        val pendingResult = goAsync()
        app.appScope.launch(Dispatchers.IO) {
            try {
                // 刷新全部小组件（闹钟到点属时间敏感同步：允许内容未变则跳过组件推送，省组合/IPC；
                // 下方重排下一批闹钟 + 提醒自愈仍每次执行，保证自维持链与提醒可靠）
                syncAllWidgets(app, allowSkipUnchanged = true)
                // 重新调度下一批关键时间点，保证链条自维持
                val ep = EntryPointAccessors.fromApplication(app, WidgetClockEntryPoint::class.java)
                ep.widgetRefreshScheduler().scheduleWidgetRefreshes()
                // 自愈：本进程是被这条闹钟唤醒的，说明部分闹钟仍存活；顺带 force 重排上下班提醒，
                // 防止 ROM 清理导致的提醒闹钟缺失长期得不到恢复。
                ep.reminderScheduler().scheduleActiveReminders(force = true)
            } catch (e: Exception) {
                AppLog.e("WidgetRefreshReceiver", "widget refresh failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}