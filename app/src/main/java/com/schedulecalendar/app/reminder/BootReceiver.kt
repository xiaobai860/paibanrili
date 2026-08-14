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

        // 使用 goAsync() 将广播生命周期延长至协程真正完成，避免系统因 onReceive 过早返回而杀进程；
        // 限定作用域仅在进程存活期有效，进程退出即停止，不会泄漏到进程外线程。
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                scheduler.scheduleUpcomingReminders()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
