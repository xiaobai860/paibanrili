package com.schedulecalendar.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.schedulecalendar.app.ScheduleApp
import com.schedulecalendar.app.widget.WidgetRefreshScheduler
import com.schedulecalendar.app.widget.syncAllWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 时间/时区变更接收器。
 *
 * AlarmManager.setAlarmClock / setExactAndAllowWhileIdle 使用绝对毫秒。用户跨时区飞行、
 * 手动修改系统时间或时区时，已注册的闹钟毫秒会错位或失效，且系统不会自动修正。
 * 此接收器在收到时间/时区变更广播时：
 * - 全量重排上下班提醒闹钟（先取消再重建）；
 * - 全量重排小组件精确刷新闹钟，并回源刷新一次组件（组件上的「今天/明天/倒计时」
 *   同样依赖本地日期时间，时区/时间变更后需立即重算）。
 */
class TimeChangeReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TimeChangeReceiverEntryPoint {
        fun reminderScheduler(): ReminderScheduler
        fun widgetRefreshScheduler(): WidgetRefreshScheduler
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
        ) return

        val ep = EntryPointAccessors
            .fromApplication(context, TimeChangeReceiverEntryPoint::class.java)
        val scheduler = ep.reminderScheduler()
        val widgetScheduler = ep.widgetRefreshScheduler()

        val pendingResult = goAsync()
        val app = context.applicationContext as ScheduleApp
        app.appScope.launch(Dispatchers.IO) {
            try {
                // 按当前方式统一重排（基于绝对毫秒，时区变更后需重建）
                scheduler.scheduleActiveReminders(force = true)
                // 小组件：回源刷新 + 重排精确刷新闹钟（本地日期时间已变，重算关键时间点）
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