package com.schedulecalendar.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 时间/时区变更接收器。
 *
 * AlarmManager.setAlarmClock 使用绝对毫秒。用户跨时区飞行、手动修改系统时间
 * 或时区时，已注册的闹钟毫秒会错位或失效，且系统不会自动修正。
 * 此接收器在收到时间/时区变更广播时全量重调度（先取消再重建），确保提醒时间准确。
 */
class TimeChangeReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TimeChangeReceiverEntryPoint {
        fun reminderScheduler(): ReminderScheduler
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
        ) return

        val scheduler = EntryPointAccessors
            .fromApplication(context, TimeChangeReceiverEntryPoint::class.java)
            .reminderScheduler()

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 重排精确闹钟（基于绝对毫秒，时区变更后需重建）
                scheduler.cancelAllReminders()
                scheduler.scheduleUpcomingReminders()
                // 重排系统日历提醒事件（跨时区/改时间后旧事件毫秒会错位）
                scheduler.rescheduleCalendarReminders()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
