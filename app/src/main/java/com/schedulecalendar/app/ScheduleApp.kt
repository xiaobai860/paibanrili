// app/src/main/java/com/schedulecalendar/app/ScheduleApp.kt
package com.schedulecalendar.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.schedulecalendar.app.widget.WidgetRefreshWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class ScheduleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 小组件周期刷新兜底：事件驱动为主，WorkManager 15min 周期兜底
        scheduleWidgetPeriodicRefresh()
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

    companion object {
        const val WIDGET_REFRESH_WORK_NAME = "widget_periodic_refresh"
    }
}
