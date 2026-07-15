package com.schedulecalendar.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机自启动接收器
 *
 * 设备重启后 AlarmManager 中的所有闹钟会被系统清除，
 * 需要在开机完成后重新调度上下班提醒。
 *
 * 使用 Hilt EntryPoint 获取 ReminderScheduler 单例，
 * 因为 BroadcastReceiver 不能直接注入依赖。
 */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootReceiverEntryPoint {
        fun reminderScheduler(): ReminderScheduler
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val scheduler = EntryPointAccessors
            .fromApplication(context, BootReceiverEntryPoint::class.java)
            .reminderScheduler()

        // 在 IO 调度器中重新调度未来 7 天的提醒
        CoroutineScope(Dispatchers.IO).launch {
            scheduler.scheduleUpcomingReminders()
        }
    }
}
