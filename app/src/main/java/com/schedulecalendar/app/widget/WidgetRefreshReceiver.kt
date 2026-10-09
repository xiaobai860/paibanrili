// app/src/main/java/com/schedulecalendar/app/widget/WidgetRefreshReceiver.kt
package com.schedulecalendar.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.schedulecalendar.app.ScheduleApp
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
                // 刷新全部小组件（回源重算 → 写入数据 → Glance update）
                syncAllWidgets(app)
                // 重新调度下一批关键时间点，保证链条自维持
                val ep = EntryPointAccessors.fromApplication(app, WidgetClockEntryPoint::class.java)
                ep.widgetRefreshScheduler().scheduleWidgetRefreshes()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }
}