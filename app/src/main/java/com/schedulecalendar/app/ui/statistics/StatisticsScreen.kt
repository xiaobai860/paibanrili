// app/src/main/java/com/schedulecalendar/app/ui/statistics/StatisticsScreen.kt
package com.schedulecalendar.app.ui.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.schedulecalendar.app.ui.hours.HoursContent
import com.schedulecalendar.app.ui.salary.SalaryContent
import kotlinx.coroutines.launch

/**
 * MD3 合并统计页：工时 + 薪资
 * 月份切换样式与事项待办页面完全一致
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatisticsScreen(navController: NavController) {
    // 共享月份状态（工时/薪资页同步，单向流动：父→子，rememberSaveable 保持导航返回后的月份）
    var sharedYear  by rememberSaveable { mutableIntStateOf(java.time.YearMonth.now().year) }
    var sharedMonth by rememberSaveable { mutableIntStateOf(java.time.YearMonth.now().monthValue) }

    val tabTitles = listOf("工时", "薪资")
    val pagerState = rememberPagerState(pageCount = { tabTitles.size })
    val currentPage = pagerState.currentPage
    val scope = rememberCoroutineScope()

    val today = java.time.LocalDate.now()
    val isNotCurrentMonth = sharedYear != today.year || sharedMonth != today.monthValue

    Scaffold(
        topBar = {
            Column {
                // M3 TabRow — 与 HorizontalPager 双向同步（顶部）
                TabRow(
                    selectedTabIndex = currentPage,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        if (currentPage < tabPositions.size) {
                            SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[currentPage]),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                ) {
                    tabTitles.forEachIndexed { i, title ->
                        Tab(
                            selected = currentPage == i,
                            onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                            text = {
                                Text(
                                    title,
                                    fontSize = 14.sp,
                                    fontWeight = if (currentPage == i) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }

                // 月份切换控件（与事项待办完全一致，中部）
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            if (sharedMonth == 1) { sharedYear--; sharedMonth = 12 }
                            else sharedMonth--
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.ChevronLeft, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("上月", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${sharedYear}年${sharedMonth}月",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isNotCurrentMonth) {
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                sharedYear = today.year
                                sharedMonth = today.monthValue
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.CalendarToday, contentDescription = "返回当月",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            if (sharedMonth == 12) { sharedYear++; sharedMonth = 1 }
                            else sharedMonth++
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("下月", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(2.dp))
                        Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                    }
                }
            }
        }
    ) { pad ->
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
        ) { page ->
            Box(Modifier.fillMaxSize()) {
                when (page) {
                    0 -> HoursContent(
                        navController = navController,
                        sharedYear = sharedYear,
                        sharedMonth = sharedMonth,
                        onMonthChange = { y, m -> sharedYear = y; sharedMonth = m }
                    )
                    1 -> SalaryContent(
                        navController = navController,
                        sharedYear = sharedYear,
                        sharedMonth = sharedMonth,
                        onMonthChange = { y, m -> sharedYear = y; sharedMonth = m }
                    )
                }
            }
        }
    }
}
