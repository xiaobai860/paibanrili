// app/src/main/java/com/schedulecalendar/app/ui/navigation/AppNavHost.kt
package com.schedulecalendar.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.*
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.schedulecalendar.app.ui.calendar.CalendarScreen
import com.schedulecalendar.app.ui.detail.DisplaySchemesScreen
import com.schedulecalendar.app.ui.detail.ExtraItemsScreen
import com.schedulecalendar.app.ui.detail.HoursDetailScreen
import com.schedulecalendar.app.ui.detail.ScheduleDetailScreen
import com.schedulecalendar.app.ui.hours.HoursScreen
import com.schedulecalendar.app.ui.salary.SalaryScreen
import com.schedulecalendar.app.ui.settings.SettingsScreen
import com.schedulecalendar.app.ui.settings.SalarySettingsScreen
import com.schedulecalendar.app.ui.settings.AttendanceSettingsScreen
import com.schedulecalendar.app.ui.settings.CalendarAccountSettingsScreen
import com.schedulecalendar.app.ui.settings.AutoClockSettingsScreen
import com.schedulecalendar.app.ui.settings.OtherSettingsScreen
import com.schedulecalendar.app.ui.settings.StorageScreen
import com.schedulecalendar.app.ui.shifts.ShiftEditorScreen
import com.schedulecalendar.app.ui.shifts.ShiftsScreen
import com.schedulecalendar.app.ui.statistics.StatisticsScreen
import com.schedulecalendar.app.ui.todo.TodoScreen
import com.schedulecalendar.app.ui.settings.ReminderSettingsScreen
import com.schedulecalendar.app.ui.calendar.HuangLiScreen

/** Tab 配置（使用类型安全路由 Any 统一持有） */
data class TabItem(val route: Any, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(RouteCalendar,   "日历",  Icons.Filled.CalendarMonth),
    TabItem(RouteTodo,       "事项",  Icons.Filled.Notifications),
    TabItem(RouteStatistics, "统计",  Icons.Filled.BarChart),
    TabItem(RouteShifts,     "班次",  Icons.Filled.Schedule),
    TabItem(RouteSettings,   "设置",  Icons.Filled.Settings)
)

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()

    val currentDest = navBackStackEntry?.destination
    val tabRouteNames = tabs.map { (it.route::class).qualifiedName }
    val showBottomBar = currentDest?.route in tabRouteNames

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = currentDest?.route == (tab.route::class).qualifiedName
                        NavigationBarItem(
                            icon     = { Icon(tab.icon, contentDescription = tab.label) },
                            label    = { Text(tab.label) },
                            selected = selected,
                            onClick  = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState    = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController    = navController,
            startDestination = RouteCalendar,
            modifier         = Modifier.padding(paddingValues)
        ) {
            // ── Tab 主页面 ──────────────────────────────────────────
            composable<RouteCalendar>   { CalendarScreen(navController) }
            composable<RouteTodo>       { TodoScreen(navController) }
            composable<RouteShifts>     { ShiftsScreen(navController) }
            composable<RouteStatistics> { StatisticsScreen(navController) }
            composable<RouteHours>      { HoursScreen(navController) }    // 保留兼容
            composable<RouteSalary>     { SalaryScreen(navController) }   // 保留兼容
            composable<RouteSettings>   { SettingsScreen(navController) }

            // ── 子页面（类型安全，参数由 SavedStateHandle.toRoute<T>() 读取）──
            composable<RouteScheduleDetail>    { ScheduleDetailScreen(navController) }
            composable<RouteHoursDetail>       { HoursDetailScreen(navController) }
            composable<RouteStorage>           { StorageScreen(navController) }
            composable<RouteSalarySettings>    { SalarySettingsScreen(navController) }
            composable<RouteAttendanceSettings> { AttendanceSettingsScreen(navController) }
            composable<RouteCalendarAccountSettings> { CalendarAccountSettingsScreen(navController) }
            composable<RouteAutoClockSettings> { AutoClockSettingsScreen(navController) }
            composable<RouteOtherSettings>     { OtherSettingsScreen(navController) }
            composable<RouteExtraItems>        { ExtraItemsScreen(navController) }
            composable<RouteDisplaySchemes>    { DisplaySchemesScreen(navController) }
            composable<RouteShiftEditor>       { ShiftEditorScreen(navController) }
            composable<RouteHuangLi> { HuangLiScreen(navController) }
            composable<RouteReminderSettings> { ReminderSettingsScreen(navController) }
        }
    }
}
