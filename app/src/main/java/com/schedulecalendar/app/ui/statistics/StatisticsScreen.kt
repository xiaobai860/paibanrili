// app/src/main/java/com/schedulecalendar/app/ui/statistics/StatisticsScreen.kt
package com.schedulecalendar.app.ui.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
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
 * 使用 M3 TopAppBar + TabRow + HorizontalPager，两个月份同步联动
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StatisticsScreen(navController: NavController) {
    // 共享月份状态（工时/薪资页同步）
    var sharedYear  by remember { mutableIntStateOf(java.time.YearMonth.now().year) }
    var sharedMonth by remember { mutableIntStateOf(java.time.YearMonth.now().monthValue) }

    val tabTitles = listOf("工时", "薪资")
    val pagerState = rememberPagerState(pageCount = { tabTitles.size })
    val currentPage = pagerState.currentPage
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            // M3 CenterAlignedTopAppBar + 月份导航
            CenterAlignedTopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(onClick = {
                            if (sharedMonth == 1) { sharedYear--; sharedMonth = 12 }
                            else sharedMonth--
                        }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上月")
                        }
                        Text(
                            "${sharedYear}年${sharedMonth}月",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        IconButton(onClick = {
                            if (sharedMonth == 12) { sharedYear++; sharedMonth = 1 }
                            else sharedMonth++
                        }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下月")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            // M3 TabRow — 与 HorizontalPager 双向同步
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
                                fontWeight = if (currentPage == i) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }
    ) { pad ->
        HorizontalPager(
            state = pagerState,
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
