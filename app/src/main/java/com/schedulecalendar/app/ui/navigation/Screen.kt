// app/src/main/java/com/schedulecalendar/app/ui/navigation/Screen.kt
package com.schedulecalendar.app.ui.navigation

import kotlinx.serialization.Serializable

// ── Tab 主页面（无参数 → object）────────────────────────────────────
@Serializable object RouteCalendar
@Serializable object RouteTodo       // 新：事项页
@Serializable object RouteShifts
@Serializable object RouteHours      // 保留兼容
@Serializable object RouteSalary     // 保留兼容
@Serializable object RouteStatistics  // 新：合并统计页
@Serializable object RouteSettings

// ── 子页面（带参数 → data class）────────────────────────────────────
@Serializable data class RouteScheduleDetail(val date: String)
@Serializable data class RouteHoursDetail(val year: Int, val month: Int, val type: String = "all")
@Serializable object RouteStorage
@Serializable object RouteSalarySettings
@Serializable object RouteAttendanceSettings
@Serializable object RouteCalendarAccountSettings
@Serializable object RouteAutoClockSettings
@Serializable object RouteOtherSettings
@Serializable object RouteExtraItems
@Serializable object RouteDisplaySchemes
@Serializable data class RouteShiftEditor(val shiftId: String? = null)
@Serializable data class RouteHuangLi(val date: String)
@Serializable object RouteReminderSettings
@Serializable object RouteAddCalendarEvent
@Serializable data class RouteEditCalendarEvent(val eventId: Long)
@Serializable object RouteAddAnniversary
@Serializable data class RouteEditAnniversary(val eventId: Long)
@Serializable object RouteWidgetSettings
