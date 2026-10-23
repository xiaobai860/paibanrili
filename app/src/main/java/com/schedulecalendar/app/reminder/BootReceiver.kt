package com.schedulecalendar.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import com.schedulecalendar.app.ScheduleApp
import com.schedulecalendar.app.widget.WidgetClockEntryPoint
import com.schedulecalendar.app.widget.WidgetRefreshScheduler
import com.schedulecalendar.app.widget.syncAllWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机自启动接收器
 *
 * 设备重启后 AlarmManager 中的所有闹钟会被系统清除，需要在开机完成后重新调度：
 * - 上下班提醒闹钟（ReminderScheduler）
 * - 小组件精确刷新闹钟（WidgetRefreshScheduler），并立即回源刷新一次组件，
 *   避免「重启后组件停留在上次状态、还要等 15min worker/Doze 才更新」。
 *
 * 使用 Hilt EntryPoint 获取调度器单例，因为 BroadcastReceiver 不能直接注入依赖。
 */
class BootReceiver : BroadcastReceiver() {

    // 依赖获取统一走 [WidgetClockEntryPoint]（已包含 reminderScheduler + widgetRefreshScheduler），
    // 不再为本接收器单独定义一份内容完全相同的 EntryPoint 接口

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        // onReceive 中任何未捕获异常都会杀死进程；goAsync() 之前的整段都要有防护
        val result = runCatching {
            val ep = EntryPointAccessors
                .fromApplication(context, WidgetClockEntryPoint::class.java)
            val scheduler = ep.reminderScheduler()
            val widgetScheduler = ep.widgetRefreshScheduler()

            val app = context.applicationContext as? ScheduleApp ?: return
            // 使用 goAsync() 将广播生命周期延长至协程真正完成，避免系统因 onReceive 过早返回而杀进程；
            // 限定作用域仅在进程存活期有效，进程退出即停止，不会泄漏到进程外线程。
            goAsync().also { pendingResult ->
                app.appScope.launch(Dispatchers.IO) {
                    try {
                        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                            // 应用更新安装后系统会清除所有 AlarmManager 闹钟，需全量重调度
                            scheduler.cancelAllRemindersSafely()
                        }
                        // 上下班提醒重排
                        scheduler.scheduleActiveReminders(force = true)
                        // 小组件：回源刷新一次 + 重排精确刷新闹钟（重启后立即生效，不等 worker）
                        runCatching { syncAllWidgets(app) }
                        runCatching { widgetScheduler.scheduleWidgetRefreshes() }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
        if (result.isFailure) result.exceptionOrNull()?.printStackTrace()
    }
}