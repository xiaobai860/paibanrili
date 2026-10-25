// app/src/main/java/com/schedulecalendar/app/domain/util/LogTags.kt
package com.schedulecalendar.app.domain.util

/**
 * 全项目日志 TAG 的唯一来源。
 *
 * 散落各处的裸字符串 TAG 会有三类问题：拼写漂移（同一模块写成 WIDGET_SYNC / WidgetSync
 * / widget_sync，过滤日志时漏一半）、来源不可辨（WBD 这种跨模块杂项 TAG，出问题无法定位
 * 是谁打的）、无法守护（字符串散落时只能靠人肉 review）。
 *
 * 命名规范（硬性）：
 * - 常量名 = Kotlin 官方 SCREAMING_SNAKE_CASE；
 * - TAG 值 = CamelCase，且与所属类名严格一致；
 * - 一个 TAG 只归属一个类/模块（唯一例外是 WIDGET_SYNC：它表示"组件同步流程"这个
 *   跨组件的概念，由 WidgetSync 与各组件的更新入口共用）。
 *
 * 调用点必须写 AppLog.x(LogTags.XXX, ...)，不得硬编码 TAG 字符串
 * （由 LoggingConventionTest 守护）。
 */
object LogTags {
    // 应用入口
    const val APP = "ScheduleApp"

    // 提醒 / 闹钟
    const val ALARM_RECEIVER = "AlarmReceiver"
    const val ANNIVERSARY_REMINDER_RECEIVER = "AnniversaryReminderReceiver"
    const val BOOT_RECEIVER = "BootReceiver"
    const val TIME_CHANGE_RECEIVER = "TimeChangeReceiver"
    const val REMINDER_SCHEDULER = "ReminderScheduler"
    const val REMINDER_SETTINGS_VIEW_MODEL = "ReminderSettingsViewModel"

    // 数据层
    const val CALENDAR_EVENT_REPOSITORY = "CalendarEventRepository"
    const val SHIFT_REPOSITORY = "ShiftRepository"
    const val SHIFT_STATUS_REPOSITORY = "ShiftStatusRepository"
    const val SHIFT_BREAK_REPOSITORY = "ShiftBreakRepository"
    const val SCHEDULE_REPOSITORY = "ScheduleRepository"
    const val EXTRA_ITEM_REPOSITORY = "ExtraItemRepository"
    const val COMPAT_GATE = "CompatGate"

    // 界面层
    const val APP_NAV_HOST = "AppNavHost"
    const val CALENDAR_SCREEN = "CalendarScreen"
    const val CALENDAR_VIEW_MODEL = "CalendarViewModel"
    const val SCHEDULE_DETAIL_SCREEN = "ScheduleDetailScreen"
    const val TODO_SCREEN = "TodoScreen"
    const val ADD_ANNIVERSARY_SCREEN = "AddAnniversaryScreen"
    const val CALENDAR_EVENT_VIEW_MODEL = "CalendarEventViewModel"
    const val CALENDAR_ACCOUNT_VIEW_MODEL = "CalendarAccountViewModel"
    const val SETTINGS_SCREEN = "SettingsScreen"
    const val STORAGE_SCREEN = "StorageScreen"

    // 备份
    const val BACKUP_MANAGER = "BackupManager"

    // 小组件
    const val WIDGET_SYNC = "WidgetSync"
    const val SCHEDULE_GLANCE_WIDGET = "ScheduleGlanceWidget"
    const val WIDGET_CLOCK_IN_ACTION = "WidgetClockInAction"
    const val WIDGET_CLOCK_OUT_ACTION = "WidgetClockOutAction"
    const val CALENDAR_GLANCE_WIDGET = "CalendarGlanceWidget"
    const val CALENDAR_3X4_GLANCE_WIDGET = "Calendar3x4GlanceWidget"
    const val WIDGET_ICON = "WidgetIcon"
    const val WIDGET_REFRESH_RECEIVER = "WidgetRefreshReceiver"
    const val WIDGET_REFRESH_WORKER = "WidgetRefreshWorker"
}
