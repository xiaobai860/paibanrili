// app/src/main/java/com/schedulecalendar/app/ScheduleApp.kt
package com.schedulecalendar.app

import android.app.Activity
import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.schedulecalendar.app.reminder.ReminderScheduler
import com.schedulecalendar.app.widget.WidgetClockEntryPoint
import com.schedulecalendar.app.widget.WidgetRefreshWorker
import com.schedulecalendar.app.widget.syncAllWidgets
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

@HiltAndroidApp
@OptIn(FlowPreview::class)
class ScheduleApp : Application() {

    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 前台 Activity 计数：归零表示应用已完全退到后台（桌面可见） */
    private var foregroundCount = 0

    /** 上下班提醒调度器（通过 Hilt EntryPoint 获取，供应用级钩子统一触发重排） */
    private lateinit var reminderScheduler: ReminderScheduler

    override fun onCreate() {
        super.onCreate()
        // 通过 Hilt EntryPoint 取得提醒调度器（与小组件数据变更信号复用同一入口）
        reminderScheduler = EntryPointAccessors.fromApplication(
            this, WidgetClockEntryPoint::class.java
        ).reminderScheduler()
        // 小组件周期刷新兜底：事件驱动为主，WorkManager 15min 周期兜底
        scheduleWidgetPeriodicRefresh()
        // 核心：任何排班/班次/附加状态数据变更 → 自动重新计算并刷新全部小组件（事件驱动，无需手动刷新）
        observeDataChangesForWidgetSync()
        // 关键：App 退到后台（桌面可见）后再同步一次——部分 OEM 桌面会丢弃 App 前台期间的组件更新，
        // 在桌面可见时刻补发一次，保证「App 改完→回桌面」时组件即为最新。
        registerBackgroundWidgetSync()
    }

    /** App 完全退到后台时，延迟到桌面切换完成后再同步一次组件（此时组件可见，更新才会被桌面应用） */
    private fun registerBackgroundWidgetSync() {
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                foregroundCount++
                // 应用从后台回到前台（含冷启动首次进入）→ 重排上下班提醒。
                // 这样「打开应用」即可刷新提醒，无需依赖设置页或排班变更（设置配好后人很少再进）。
                if (foregroundCount == 1) {
                    appScope.launch { runCatching { reminderScheduler.scheduleActiveReminders() } }
                }
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                foregroundCount--
                if (foregroundCount <= 0) {
                    // 两段式补发：1.5s 与 4s 各同步一次，覆盖不同桌面切换/稳定时间（OEM 桌面会丢弃切换窗口期内的更新）
                    for (delayMs in longArrayOf(1500, 4000)) {
                        appScope.launch {
                            delay(delayMs)
                            runCatching { syncAllWidgets(this@ScheduleApp) }
                        }
                    }
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun scheduleWidgetPeriodicRefresh() {
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WIDGET_REFRESH_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * 全局监听数据变更信号（排班/班次/附加状态），去抖合并后回源数据库重算并刷新全部小组件。
     * 这样 App 内任何修改（改班次、改班次定义、批量排班、打卡等）落库后，组件都会立即自动更新，
     * 不再依赖用户手动点刷新，也不依赖日历页是否打开。
     *
     * 同一数据流也驱动上下班提醒重排：无论变更来自「排班详情页保存」「日历批量排班/清除」
     * 还是「小组件直接打卡」（均经 ScheduleRepository 的 refreshSignal），提醒都会按最新排班刷新——
     * 即使用户只点小组件、不进应用也能触发。
     */
    private fun observeDataChangesForWidgetSync() {
        appScope.launch {
            val ep = EntryPointAccessors.fromApplication(
                this@ScheduleApp, WidgetClockEntryPoint::class.java
            )
            merge(
                ep.scheduleRepository().refreshSignal,
                ep.shiftRepository().changeSignal,
                ep.shiftStatusRepository().changeSignal
            )
                .debounce(400)
                .collect {
                    runCatching { syncAllWidgets(this@ScheduleApp) }
                    runCatching { reminderScheduler.scheduleActiveReminders() }
                }
        }
        // 进程冷启动（如桌面组件点击唤醒进程）后同步一次，保证组件始终是最新数据；
        // 同时重排一次提醒，覆盖「仅点小组件唤醒进程、未进应用」的场景。
        appScope.launch {
            delay(1000)
            runCatching { syncAllWidgets(this@ScheduleApp) }
            runCatching { reminderScheduler.scheduleActiveReminders() }
        }
    }

    companion object {
        const val WIDGET_REFRESH_WORK_NAME = "widget_periodic_refresh"
    }
}
