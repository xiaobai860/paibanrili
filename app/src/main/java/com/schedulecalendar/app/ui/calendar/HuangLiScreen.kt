// app/src/main/java/com/schedulecalendar/app/ui/calendar/HuangLiScreen.kt
package com.schedulecalendar.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.toRoute
import com.schedulecalendar.app.domain.model.LunarCalendar
import com.schedulecalendar.app.domain.model.LunarCalendar.ShiChen
import com.schedulecalendar.app.ui.navigation.RouteHuangLi
import com.schedulecalendar.app.ui.theme.Green700
import java.time.DayOfWeek
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuangLiScreen(navController: NavController) {
    val date = remember {
        navController.currentBackStackEntry?.toRoute<RouteHuangLi>()?.date
            ?: LocalDate.now().let { "%04d-%02d-%02d".format(it.year, it.monthValue, it.dayOfMonth) }
    }

    val parts = date.split("-")
    val year = parts.getOrNull(0)?.toIntOrNull() ?: LocalDate.now().year
    val month = parts.getOrNull(1)?.toIntOrNull() ?: LocalDate.now().monthValue
    val day = parts.getOrNull(2)?.toIntOrNull() ?: LocalDate.now().dayOfMonth

    val huangLi = remember(date) { LunarCalendar.getFullHuangLi(year, month, day) }

    val weekDay = remember(date) {
        val dow = LocalDate.of(year, month, day).dayOfWeek
        when (dow) {
            DayOfWeek.MONDAY -> "周一"; DayOfWeek.TUESDAY -> "周二"
            DayOfWeek.WEDNESDAY -> "周三"; DayOfWeek.THURSDAY -> "周四"
            DayOfWeek.FRIDAY -> "周五"; DayOfWeek.SATURDAY -> "周六"
            DayOfWeek.SUNDAY -> "周日"
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("黄历详情") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                // 与其他 Tab 页（事项/统计/班次）保持一致：顶格显示，不带状态栏空白
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ══════════════════════════════════════════════════════════
            // 顶部日期 Hero 卡：日期 + 农历/干支 + 宜忌
            // ══════════════════════════════════════════════════════════
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 第一行：日期大数字 + 农历/干支
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$day",
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            lineHeight = 52.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.width(72.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "${huangLi.lunar.monthText}${huangLi.lunar.dayText} $weekDay",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "${huangLi.lunar.yearGanZhi}·${huangLi.lunar.monthGanZhi}·${huangLi.lunar.dayGanZhi}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            // 节气 / 节日（可选）
                            val tag = huangLi.solarTerm ?: huangLi.festival
                            if (tag != null) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
                                ) {
                                    Text(
                                        text = tag,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
                    )

                    // 第二行：宜（绿）
                    Row(verticalAlignment = Alignment.Top) {
                        YiJiBadge(text = "宜", color = Green700)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = huangLi.huangLi.yi.joinToString("、").ifEmpty { "无" },
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // 第三行：忌（红）
                    Row(verticalAlignment = Alignment.Top) {
                        YiJiBadge(text = "忌", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = huangLi.huangLi.ji.joinToString("、").ifEmpty { "无" },
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ══════════════════════════════════════════════════════════
            // 今日信息卡：胎神/相冲/彭祖/吉神/凶煞/建除/值神/星宿/五行
            // ══════════════════════════════════════════════════════════
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    SectionTitle("今日信息")

                    InfoRow("胎神", huangLi.taiShen)
                    InfoRow("相冲", huangLi.chongSha)
                    InfoRow("彭祖百忌", huangLi.pengZu)
                    InfoRow("吉神宜趋", huangLi.jiShen.joinToString("、"))
                    InfoRow("凶煞宜忌", huangLi.xiongSha.joinToString("、"))

                    Spacer(Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.Top) {
                        InfoLabel("建除", Modifier.width(68.dp))
                        Text("${huangLi.zhiChu}日", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f))
                        InfoLabel("值神", Modifier.width(48.dp))
                        Text(huangLi.zhiShen, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        InfoLabel("星宿", Modifier.width(68.dp))
                        Text(huangLi.xingXiu, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f))
                        InfoLabel("五行", Modifier.width(48.dp))
                        Text(huangLi.naYinWuXing, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f))
                    }
                }
            }

            // ══════════════════════════════════════════════════════════
            // 吉时卡：12 时辰 2 行 × 6 列网格
            // ══════════════════════════════════════════════════════════
            if (huangLi.shiChen.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp)
                    ) {
                        SectionTitle("吉时")
                        Spacer(Modifier.height(12.dp))
                        ShiChenGrid(huangLi.shiChen)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

// ── 辅助组件 ──────────────────────────────────────────────────

/** 区块标题 */
@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

/** 宜/忌 胶囊标签 */
@Composable
private fun YiJiBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color,
        modifier = Modifier.width(34.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 3.dp)
        )
    }
}

/** 信息区标签 */
@Composable
private fun InfoLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}

/** 单行信息：标签 + 内容 + 分隔线 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        InfoLabel(label, Modifier.width(68.dp))
        Text(
            text = value.ifEmpty { "—" },
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
}

/** 吉时网格：2 行 × 6 列 */
@Composable
private fun ShiChenGrid(shiChen: List<ShiChen>) {
    val row1 = shiChen.take(6)
    val row2 = shiChen.drop(6)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            row1.forEach { sc -> ShiChenCell(sc, Modifier.weight(1f)) }
        }
        if (row2.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row2.forEach { sc -> ShiChenCell(sc, Modifier.weight(1f)) }
            }
        }
    }
}

/** 单个时辰卡片 */
@Composable
private fun ShiChenCell(sc: ShiChen, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (sc.isGood) Green700.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 2.dp)
        ) {
            Text(
                text = sc.ganZhi,
                fontSize = 12.sp,
                fontWeight = if (sc.isGood) FontWeight.Bold else FontWeight.Normal,
                color = if (sc.isGood) Green700 else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = sc.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${sc.startTime}-${sc.endTime}",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = if (sc.isGood) "吉" else "凶",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (sc.isGood) Green700 else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
