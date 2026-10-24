// app/src/main/java/com/schedulecalendar/app/ui/navigation/Screen.kt
package com.schedulecalendar.app.ui.navigation

import kotlinx.serialization.Serializable

// ── Tab 主页面（无参数 → object）────────────────────────────────────
@Serializable object RouteCalendar
/**
 * 事项页。
 *
 * `focusMonth`：进入时定位的月份（"yyyy-MM"）。
 * 头部「N 条待办待处理」用它把待办所在的月份带过来——
 * 否则从日历（上个月）跳进来会停在本月，**那条上个月的待办既看不到也找不到**
 * （待办按月加载，事项页与日历页还是两个不同的 ViewModel 实例，月份互不相通）。
 * 为 null = 不指定（底部 Tab 进入时用本月）。
 */
@Serializable data class RouteTodo(val focusMonth: String? = null)
@Serializable object RouteShifts

@Serializable object RouteStatistics  // 新：合并统计页
@Serializable object RouteSettings

// ── 子页面（带参数 → data class）────────────────────────────────────
@Serializable data class RouteScheduleDetail(val date: String)
@Serializable data class RouteHoursDetail(val year: Int, val month: Int, val type: String = "all")
@Serializable object RouteStorage
@Serializable object RouteSalarySettings
@Serializable object RouteAttendanceSettings
@Serializable object RouteCalendarAccountSettings

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
@Serializable object RouteCalcProfiles
