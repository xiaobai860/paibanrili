// app/src/main/java/com/schedulecalendar/app/ui/navigation/AppNavHost.kt
package com.schedulecalendar.app.ui.navigation
import android.util.Log
import com.schedulecalendar.app.BuildConfig

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.*

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
import com.schedulecalendar.app.ui.todo.AddCalendarEventScreen
import com.schedulecalendar.app.ui.todo.EditCalendarEventScreen
import com.schedulecalendar.app.ui.todo.AddAnniversaryScreen
import com.schedulecalendar.app.ui.settings.ReminderSettingsScreen
import com.schedulecalendar.app.ui.settings.WidgetSettingsScreen
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

// ── 转场动画 ──────────────────────────────────────────────────────────────────
// 设计：
// 1. **二级页面**用「横向推入 + 视差」：新页从右侧整屏推入，旧页只向左平移 1/5 宽，
//    形成"推叠"的层次感，方向明确（进下一级 = 向右推开，返回 = 向右退出）。
//    视差用 1/5 而非 1：位移距离越小，两屏同时绘制时的重绘开销越低，也更接近现代 Android 的观感。
// 2. **旧页退出时不做淡出**：淡出到全透明会露出窗口底色，快速切换时看起来像"闪一下"。
// 3. **Tab 主页面**用淡入淡出：底部导航切换语义上不是"进入下一级"，横向滑动会误导层级，
//    且与底部导航栏自身的淡入淡出保持一致。
private const val PAGE_ENTER_MS   = 260
private const val PAGE_EXIT_MS    = 260
private const val TAB_FADE_MS     = 180

/** 底部导航栏：让位要果断（退出更快），出现稍慢（等页面基本落位再上来） */
private const val BOTTOM_BAR_ENTER_MS       = 260
private const val BOTTOM_BAR_ENTER_DELAY_MS = 60
private const val BOTTOM_BAR_EXIT_MS        = 160

/** 慢出慢入，避免 `tween` 默认线性曲线的生硬感 */
private val MotionEasing = FastOutSlowInEasing

/** 进入二级页：整屏从右侧推入 + 淡入 */
private val secondaryEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(animationSpec = tween(PAGE_ENTER_MS, easing = MotionEasing)) { fullWidth -> fullWidth } +
        fadeIn(animationSpec = tween(PAGE_ENTER_MS, easing = MotionEasing))
}

/** 离开二级页（打开新页时）：旧页向左做 1/5 视差平移 */
private val secondaryExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(animationSpec = tween(PAGE_EXIT_MS, easing = MotionEasing)) { fullWidth -> -fullWidth / 5 }
}

/** 返回上一级：上一级页面从左侧 1/5 视差滑回 + 淡入 */
private val secondaryPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(animationSpec = tween(PAGE_ENTER_MS, easing = MotionEasing)) { fullWidth -> -fullWidth / 5 } +
        fadeIn(animationSpec = tween(PAGE_ENTER_MS, easing = MotionEasing))
}

/** 返回上一级：当前页整屏滑出到右侧 */
private val secondaryPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(animationSpec = tween(PAGE_EXIT_MS, easing = MotionEasing)) { fullWidth -> fullWidth }
}

/** Tab 切换：淡入 */
private val tabEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
    { fadeIn(animationSpec = tween(TAB_FADE_MS, easing = MotionEasing)) }

/** Tab 切换：淡出 */
private val tabExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
    { fadeOut(animationSpec = tween(TAB_FADE_MS, easing = MotionEasing)) }

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()

    if (BuildConfig.DEBUG) Log.e("WBD", "navhost: dest=" + (navBackStackEntry?.destination?.route ?: "null"))
    val currentDest = navBackStackEntry?.destination
    val tabRouteNames = tabs.map { (it.route::class).qualifiedName }
    val showBottomBar = currentDest?.route in tabRouteNames

    val context = LocalContext.current

    // 用 Compose MutableState 追踪日历子模式状态，保证 BackHandler 可响应状态变化
    var calendarSubModeActive by remember { mutableStateOf(false) }

    // 底部导航防抖：限制最小点击间隔，避免快速连续点击触发多次导航
    var lastNavClickTime by remember { mutableLongStateOf(0L) }
    val navDebounceIntervalMs = 300L

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                // 与页面转场同一套缓动，避免"闪现"：
                //   进入二级页 → 导航栏**向下滑出** + 淡出（更快，让位要果断）；
                //   返回      → 从下方**滑入**   + 淡入（略延迟，等页面基本落位再出现）。
                // 位移只在「进入/离开二级页」时触发 —— Tab 互切时 showBottomBar 恒为 true
                // （五个 Tab 都在 tabRouteNames 里），所以不会在快速切 Tab 时反复触发整条导航栏的布局/绘制。
                enter = slideInVertically(
                    animationSpec = tween(BOTTOM_BAR_ENTER_MS, delayMillis = BOTTOM_BAR_ENTER_DELAY_MS, easing = MotionEasing)
                ) { fullHeight -> fullHeight } + fadeIn(
                    animationSpec = tween(BOTTOM_BAR_ENTER_MS, delayMillis = BOTTOM_BAR_ENTER_DELAY_MS, easing = MotionEasing)
                ),
                exit = slideOutVertically(
                    animationSpec = tween(BOTTOM_BAR_EXIT_MS, easing = MotionEasing)
                ) { fullHeight -> fullHeight } + fadeOut(
                    animationSpec = tween(BOTTOM_BAR_EXIT_MS, easing = MotionEasing)
                )
            ) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = currentDest?.route == (tab.route::class).qualifiedName
                        NavigationBarItem(
                            icon     = { Icon(tab.icon, contentDescription = tab.label) },
                            label    = { Text(tab.label) },
                            selected = selected,
                            onClick  = {
                                // 已选中的 Tab 不重复导航
                                if (selected) return@NavigationBarItem
                                // 防抖：限制最小点击间隔，避免快速切换导致重组任务堆积
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - lastNavClickTime < navDebounceIntervalMs) return@NavigationBarItem
                                lastNavClickTime = now
                                // 切 Tab 时重置日历子模式（批量/复制/删除）状态，避免残留影响其他页返回键
                                calendarSubModeActive = false
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.startDestinationRoute ?: return@navigate) {
                                        saveState = true
                                        inclusive = true
                                    }
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
            modifier         = Modifier.padding(paddingValues),
            // 二级页面默认：横向推入推出（见文件顶部「转场动画」注释）；Tab 页在下面各自覆盖为淡入淡出。
            // ⚠️ 历史背景：这里曾因"与 ModalBottomSheet 关闭动画叠加导致 ColorOS 偶发渲染冻结（白屏 1-2 秒自愈）"
            // 而退化成 120ms 纯淡入淡出。现改为滑动 + 视差，位移与透明度都是 GPU 合成层操作、开销相当，
            // 若再出现同类冻结，优先排查弹窗与转场叠加，而不是退回 120ms（那个时长太短，观感像硬切）。
            enterTransition     = secondaryEnter,
            exitTransition      = secondaryExit,
            popEnterTransition  = secondaryPopEnter,
            popExitTransition   = secondaryPopExit
        ) {
            // ── Tab 主页面（淡入淡出，不走横向滑入）───────────────────
            composable<RouteCalendar>(
                enterTransition = tabEnter, exitTransition = tabExit,
                popEnterTransition = tabEnter, popExitTransition = tabExit,
                content = { CalendarScreen(navController, onSubModeChange = { calendarSubModeActive = it }) }
            )
            composable<RouteTodo>(
                enterTransition = tabEnter, exitTransition = tabExit,
                popEnterTransition = tabEnter, popExitTransition = tabExit,
                content = { TodoScreen(navController) }
            )
            composable<RouteShifts>(
                enterTransition = tabEnter, exitTransition = tabExit,
                popEnterTransition = tabEnter, popExitTransition = tabExit
            ) { ShiftsScreen(navController) }
            composable<RouteStatistics>(
                enterTransition = tabEnter, exitTransition = tabExit,
                popEnterTransition = tabEnter, popExitTransition = tabExit
            ) { StatisticsScreen(navController) }
            composable<RouteHours>      { HoursScreen(navController) }    // 保留兼容
            composable<RouteSalary>     { SalaryScreen(navController) }   // 保留兼容
            composable<RouteSettings>(
                enterTransition = tabEnter, exitTransition = tabExit,
                popEnterTransition = tabEnter, popExitTransition = tabExit
            ) { SettingsScreen(navController) }

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
            composable<RouteAddCalendarEvent> { AddCalendarEventScreen(navController) }
            composable<RouteEditCalendarEvent> { backStackEntry ->
                val route = backStackEntry.toRoute<RouteEditCalendarEvent>()
                EditCalendarEventScreen(eventId = route.eventId, navController = navController)
            }
            composable<RouteAddAnniversary> { AddAnniversaryScreen(navController) }
            composable<RouteEditAnniversary> { backStackEntry ->
                val route = backStackEntry.toRoute<RouteEditAnniversary>()
                AddAnniversaryScreen(navController = navController, eventId = route.eventId)
            }
            composable<RouteWidgetSettings> { WidgetSettingsScreen(navController) }
        }

        // 重写返回键：当在 Tab 页面时拦截 popBackStack，直接 finish Activity。
        // BackHandler 放在 NavHost 之后组合（后注册 → 优先级高于 NavHost 的返回处理），
        // 确保在任意 Tab 页按返回一次直接退出，而不会被 NavHost 先 popBackStack 回退到上一级。
        BackHandler(enabled = showBottomBar && !calendarSubModeActive) {
            if (BuildConfig.DEBUG) Log.e("WBD", "navhost: BACK pressed (finish) dest=" + (currentDest?.route ?: "null"))
            val act = context as? Activity
            if (act != null && !act.isFinishing) {
                act.finishAndRemoveTask()
            }
        }
    }
}
