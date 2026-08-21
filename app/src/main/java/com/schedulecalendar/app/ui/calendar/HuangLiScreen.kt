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
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("黄历详情") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // ══════════════════════════════════════════════════════════
            // 顶部日期卡片区：左侧大号日期数字 + 右侧农历/干支/宜忌全文
            // ══════════════════════════════════════════════════════════
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 左侧：大号日期数字
                    Text(
                        text = "$day",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Light,
                        color = Color(0xFF1A1A1A),
                        lineHeight = 48.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.width(60.dp)
                    )

                    // 右侧：全部信息
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 农历日期 + 星期
                        Text(
                            text = "${huangLi.lunar.monthText}${huangLi.lunar.dayText} $weekDay",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1A1A1A)
                        )

                        // 年干支·月干支·日干支
                        Text(
                            text = "${huangLi.lunar.yearGanZhi}·${huangLi.lunar.monthGanZhi}·${huangLi.lunar.dayGanZhi}",
                            fontSize = 14.sp,
                            color = Color(0xFF666666)
                        )

                        // 宜（绿色标签 + 完整列表）
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "宜",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Green700,
                                modifier = Modifier.width(30.dp)
                            )
                            Text(
                                text = huangLi.huangLi.yi.joinToString(", "),
                                fontSize = 15.sp,
                                color = Color(0xFF333333),
                                lineHeight = 24.sp
                            )
                        }

                        // 忌（红色标签 + 完整列表）
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "忌",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.width(30.dp)
                            )
                            Text(
                                text = huangLi.huangLi.ji.joinToString(", "),
                                fontSize = 15.sp,
                                color = Color(0xFF333333),
                                lineHeight = 24.sp
                            )
                        }
                    }
                }
            }

            // ══════════════════════════════════════════════════════════
            // 信息区卡片：胎神/相冲/彭祖百忌/吉神凶神/建除值神/星宿五行
            // ══════════════════════════════════════════════════════════
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 胎神
                    InfoRowSimple("胎神", huangLi.taiShen)

                    // 相冲
                    InfoRowSimple("相冲", huangLi.chongSha)

                    // 彭祖百忌
                    InfoRowSimple("彭祖百忌", huangLi.pengZu)

                    // 吉神宜趋
                    InfoRowSimple("吉神宜趋", huangLi.jiShen.joinToString(", "))

                    // 凶煞宜忌
                    InfoRowSimple("凶煞宜忌", huangLi.xiongSha.joinToString(", "))

                    // 建除十二神 + 值神（同行两列）
                    InfoRowDouble(
                        label1 = "建除十二神", value1 = "${huangLi.zhiChu}日",
                        label2 = "值神", value2 = huangLi.zhiShen
                    )

                    // 星宿 + 五行（同行两列）
                    InfoRowDouble(
                        label1 = "星宿", value1 = huangLi.xingXiu,
                        label2 = "五行", value2 = huangLi.naYinWuXing
                    )
                }
            }

            // ══════════════════════════════════════════════════════════
            // 吉时区：12列网格
            // ══════════════════════════════════════════════════════════
            if (huangLi.shiChen.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Text(
                            text = "吉时",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1A1A1A)
                        )

                        Spacer(Modifier.height(12.dp))

                        // 12列网格：每列包含干支、动物、时间范围、吉凶
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            huangLi.shiChen.forEach { sc ->
                                ShiChenColumn(sc)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── 辅助组件 ──────────────────────────────────────────────────

@Composable
private fun InfoRowSimple(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF333333),
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            fontSize = 15.sp,
            color = Color(0xFF333333),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun InfoRowDouble(
    label1: String, value1: String,
    label2: String, value2: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label1,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF333333),
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value1,
            fontSize = 15.sp,
            color = Color(0xFF333333),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label2,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF333333),
            modifier = Modifier.width(48.dp)
        )
        Text(
            text = value2,
            fontSize = 15.sp,
            color = Color(0xFF333333),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ShiChenColumn(sc: ShiChen) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(24.dp)
    ) {
        // 干支（上方汉字 + 下方汉字）
        Text(
            text = sc.ganZhi[0].toString(),
            fontSize = 11.sp,
            color = Color(0xFF333333)
        )
        Text(
            text = sc.ganZhi[1].toString(),
            fontSize = 11.sp,
            color = Color(0xFF333333)
        )
        // 动物
        Text(
            text = sc.name,
            fontSize = 11.sp,
            color = Color(0xFF333333)
        )
        // 时间范围
        Text(
            text = sc.startTime,
            fontSize = 10.sp,
            color = if (sc.isGood) Green700 else Color(0xFF333333)
        )
        Text(
            text = sc.endTime,
            fontSize = 10.sp,
            color = if (sc.isGood) Green700 else Color(0xFF333333)
        )
        // 吉/凶
        Text(
            text = if (sc.isGood) "吉" else "凶",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (sc.isGood) Green700 else Color(0xFF999999)
        )
    }
}
