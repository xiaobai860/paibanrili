// app/src/main/java/com/schedulecalendar/app/ScheduleApp.kt
package com.schedulecalendar.app

import android.app.Activity
import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.schedulecalendar.app.reminder.ReminderScheduler
import com.schedulecalendar.app.widget.WidgetClockEntryPoint
import com.schedulecalendar.app.widget.WidgetRefreshScheduler
import com.schedulecalendar.app.widget.WidgetRefreshWorker
import com.schedulecalendar.app.widget.syncAllWidgets
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

@HiltAndroidApp
@OptIn(FlowPreview::class)
class ScheduleApp : Application() {

    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 计算口径档案仓库（迁移后幂等创建初始档案） */
    @Inject lateinit var calcProfileRepository: com.schedulecalendar.app.data.repository.CalcProfileRepository

    /** 前台 Activity 计数：归零表示应用已完全退到后台（桌面可见） */
    private var foregroundCount = 0

    /** 上下班提醒调度器（通过 Hilt EntryPoint 获取，供应用级钩子统一触发重排） */
    private lateinit var reminderScheduler: ReminderScheduler

    /** 小组件精确刷新调度器（关键时间点准时刷新组件，弥补 15min worker/Doze 的延迟） */
    private lateinit var widgetRefreshScheduler: WidgetRefreshScheduler

    override fun onCreate() {
        super.onCreate()
        // 通过 Hilt EntryPoint 取得提醒调度器与小组件刷新调度器（与小组件数据变更信号复用同一入口）
        val ep = EntryPointAccessors.fromApplication(this, WidgetClockEntryPoint::class.java)
        reminderScheduler = ep.reminderScheduler()
        widgetRefreshScheduler = ep.widgetRefreshScheduler()
        // 小组件周期刷新兜底：事件驱动为主，WorkManager 15min 周期兜底
        scheduleWidgetPeriodicRefresh()
        // 计算口径档案：首次启动（含 v5→v6 迁移后）幂等创建初始档案，
        // 内容取迁移时刻的现值（薪资/考勤/休息时段）→ 保证历史月份计算结果逐分不变
        appScope.launch {
            runSafely("ensureInitialProfile") { calcProfileRepository.ensureInitialProfile() }
            // 接力自愈：修复早期版本「重复复制导致多条同时生效中」的坏数据。
            // 只重设生效区间（+ 清理同日重复的残留），不改变任何档案内容。
            runSafely("repairRelay") { calcProfileRepository.repairRelay() }
        }
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
                    appScope.launch { runSafely("scheduleActiveReminders(foreground)") { reminderScheduler.scheduleActiveReminders() } }
                }
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                foregroundCount--
                if (foregroundCount <= 0) {
                    // 两段式补发：1.5s 与 4s 各同步一次，覆盖不同桌面切换/稳定时间（OEM 桌面会丢弃切换窗口期内的更新）
                    for (delayMs in BACKGROUND_SYNC_DELAYS_MS) {
                        appScope.launch {
                            delay(delayMs)
                            runSafely("syncAllWidgets(background:$delayMs)") { syncAllWidgets(this@ScheduleApp) }
                        }
                    }
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun scheduleWidgetPeriodicRefresh() {
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(WIDGET_REFRESH_PERIOD_MIN, TimeUnit.MINUTES)
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
                ep.shiftStatusRepository().changeSignal,
                ep.shiftBreakRepository().changeSignal,
                ep.extraItemRepository().changeSignal
            )
                .debounce(WIDGET_SYNC_DEBOUNCE_MS)
                .collect {
                    runSafely("syncAllWidgets(changeSignal)") { syncAllWidgets(this@ScheduleApp) }
                    runSafely("scheduleActiveReminders(changeSignal)") { reminderScheduler.scheduleActiveReminders() }
                    // 排班/班次/状态变更可能改动班次时刻 → 重排小组件刷新闹钟，保持关键时间点准确
                    runSafely("scheduleWidgetRefreshes(changeSignal)") { widgetRefreshScheduler.scheduleWidgetRefreshes() }
                }
        }
        // 进程一起来【立即】注册上下班提醒闹钟 + 小组件刷新闹钟（不做任何延迟）：
        // 冷启动往往是被闹钟/组件在后台唤醒的，无可见 Activity，进程随时可能被系统回收；
        // 若延迟注册，进程在延迟窗口内被杀就会导致提醒闹钟一直缺失（表现为「不提醒」）。
        appScope.launch {
            // 两个调度并行执行：原先串行 await，冷启动要等两轮（含各自的 DB/DataStore 读取）
            val reminders = async { runSafely("scheduleActiveReminders(coldStart)") { reminderScheduler.scheduleActiveReminders() } }
            val widgets = async { runSafely("scheduleWidgetRefreshes(coldStart)") { widgetRefreshScheduler.scheduleWidgetRefreshes() } }
            reminders.await()
            widgets.await()
        }
        // 组件数据回源同步可稍延迟（避免冷启动抢资源，留时间给进程稳定），保留 1s
        appScope.launch {
            delay(COLD_START_SYNC_DELAY_MS)
            runSafely("syncAllWidgets(coldStart)") { syncAllWidgets(this@ScheduleApp) }
        }
    }

    /**
     * 统一的任务保护：这些任务失败的直接后果是「提醒不弹 / 组件不刷新」，
     * 且没有任何 UI 线索，因此必须记日志而不是静默吞掉异常。
     */
    private suspend fun runSafely(tag: String, block: suspend () -> Unit) {
        runCatching { block() }.onFailure {
            android.util.Log.w("ScheduleApp", "$tag failed", it)
        }
    }

    companion object {
        const val WIDGET_REFRESH_WORK_NAME = "widget_periodic_refresh"
        /** 数据变更去抖窗口（毫秒）：合并连续写入，避免刷新风暴 */
        private const val WIDGET_SYNC_DEBOUNCE_MS = 400L
        /** 退到后台后的补发延迟（毫秒）：覆盖不同 OEM 桌面的切换/稳定时间 */
        private val BACKGROUND_SYNC_DELAYS_MS = longArrayOf(1500L, 4000L)
        /** 冷启动后组件回源同步延迟（毫秒） */
        private const val COLD_START_SYNC_DELAY_MS = 1000L
        /** WorkManager 兜底刷新周期（分钟） */
        private const val WIDGET_REFRESH_PERIOD_MIN = 15L
    }
}
