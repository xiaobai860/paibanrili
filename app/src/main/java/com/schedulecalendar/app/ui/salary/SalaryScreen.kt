// app/src/main/java/com/schedulecalendar/app/ui/salary/SalaryScreen.kt
package com.schedulecalendar.app.ui.salary

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.core.graphics.toColorInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.domain.model.DayScheduleDetail
import com.schedulecalendar.app.domain.model.SalarySummary
import com.schedulecalendar.app.domain.model.ScheduleType
import com.schedulecalendar.app.domain.model.BUILTIN_SHIFTS
import com.schedulecalendar.app.ui.component.MonthNavigator
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import com.schedulecalendar.app.ui.detail.safeColor
import kotlin.math.max

private fun fmtY(v: Double): String = "%.2f".format(v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalaryScreen(navController: NavController, vm: SalaryViewModel = hiltViewModel()) {
    SalaryContent(
        navController = navController,
        sharedYear = null,
        sharedMonth = null,
        onMonthChange = { _, _ -> },
        vm = vm
    )
}

/** 嵌入式内容：由 StatisticsScreen 传入共享月份 */
@Composable
fun SalaryContent(
    navController: NavController,
    sharedYear: Int?,
    sharedMonth: Int?,
    modifier: Modifier = Modifier,
    onMonthChange: (Int, Int) -> Unit = { _, _ -> },
    vm: SalaryViewModel = hiltViewModel()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.reload()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 外部共享月份同步
    LaunchedEffect(sharedYear, sharedMonth) {
        if (sharedYear != null && sharedMonth != null) {
            vm.goToMonth(sharedYear, sharedMonth)
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.year, state.month) {
        if (sharedYear != null) onMonthChange(state.year, state.month)
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.uiEvent.collect { event ->
            when (event) {
                is SalaryUiEvent.ShowError -> snackbar.showSnackbar(event.message)
            }
        }
    }

    var chartView by remember { mutableStateOf("pie") }
    val isEmbedded = sharedYear != null
    val contentBlock: @Composable (PaddingValues) -> Unit = { padding ->
        if (state.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val actual = state.actual
            val future = state.future
            val details = state.details

            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
            // ── 月收入总卡 ────────────────────────────────────────────
            item {
                Surface(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("本月预计薪资", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f), fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("¥${fmtY(state.fullEstimate.totalSalary)}",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 36.sp, fontWeight = FontWeight.Bold)
                        if (future != null && future.totalSalary > 0) {
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("已到 ¥${fmtY(actual.totalSalary)}",
                                    color = Color(0xFF86EFAC), fontSize = 13.sp)
                                Text("预计再到 ¥${fmtY(future.totalSalary)}",
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // ── 薪资明细网格 ──────────────────────────────────────────
            item { SalaryDetailGrid(actual, future) }

            // ── 图表区 ────────────────────────────────────────────────
            item {
                SalaryChartCard(
                    chartView  = chartView,
                    onChange   = { chartView = it },
                    actual     = actual,
                    trend      = state.trend
                )
            }

            // ── 每日明细 ──────────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).height(18.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text("每日明细", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
                Spacer(Modifier.height(6.dp))
            }

            val workDays = details.filter { it.record != null }.reversed()
            if (workDays.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("本月未设置排班", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(workDays, key = { it.date }) { d ->
                    SalaryDailyRow(d)
                }
            }
            }
        }
    }

    if (isEmbedded) {
        contentBlock(PaddingValues(0.dp))
    } else {
        Scaffold(
            topBar = {
                ScheduleTopBar("薪资记录", actions = {
                    MonthNavigator(state.year, state.month, vm::goToPrevMonth, vm::goToNextMonth)
                })
            },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            contentBlock(padding)
        }
    }
}

@Composable
private fun SalaryDetailGrid(actual: SalarySummary, future: SalarySummary?) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 底薪 + 绩效
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SalaryStatCell("基础底薪", actual.baseSalary,
                future = null, modifier = Modifier.weight(1f))
            SalaryStatCell("基础绩效", actual.basePerformance,
                future = null, modifier = Modifier.weight(1f))
        }
        // 正常工资 + 加班工资
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SalaryStatCell("正常工资", actual.normalSalary,
                future = future?.normalSalary?.takeIf { it > 0 }, modifier = Modifier.weight(1f))
            SalaryStatCell("加班工资", actual.overtimeSalary,
                future = future?.overtimeSalary?.takeIf { it > 0 },
                valueColor = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        }
        // 周末工资 + 节假日工资
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SalaryStatCell("周末工资", actual.weekendSalary,
                future = future?.weekendSalary?.takeIf { it > 0 }, modifier = Modifier.weight(1f))
            SalaryStatCell("节假日工资", actual.holidaySalary,
                future = future?.holidaySalary?.takeIf { it > 0 }, modifier = Modifier.weight(1f))
        }
        // 补贴合计 + 扣款合计
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SalaryStatCell("补贴合计", actual.totalSubsidy,
                future = future?.totalSubsidy?.takeIf { it > 0 },
                valueColor = Color(0xFF16A34A), modifier = Modifier.weight(1f))
            SalaryStatCell("扣款合计", actual.totalDeduction,
                future = future?.totalDeduction?.takeIf { it > 0 },
                valueColor = MaterialTheme.colorScheme.error,
                isDeduction = true, modifier = Modifier.weight(1f))
        }
        // 社保 + 个税（仅配置了才显示）
        if (actual.socialInsurance > 0 || actual.housingFundDeduction > 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (actual.socialInsurance > 0)
                    SalaryStatCell("社保扣款", actual.socialInsurance,
                        valueColor = MaterialTheme.colorScheme.error,
                        isDeduction = true, modifier = Modifier.weight(1f))
                if (actual.housingFundDeduction > 0)
                    SalaryStatCell("公积金扣款", actual.housingFundDeduction,
                        valueColor = MaterialTheme.colorScheme.error,
                        isDeduction = true, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SalaryStatCell(
    label: String, value: Double,
    modifier: Modifier = Modifier,
    future: Double? = null,
    valueColor: Color? = null,
    isDeduction: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${if (isDeduction) "-" else ""}¥${fmtY(value)}",
                    fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    color = valueColor ?: MaterialTheme.colorScheme.onSurface
                )
                if (future != null && future > 0) {
                    Text("+¥${fmtY(future)}", fontSize = 11.sp, color = Color(0xFF16A34A))
                }
            }
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SalaryChartCard(
    chartView: String, onChange: (String) -> Unit,
    actual: SalarySummary,
    trend: List<MonthlySalaryTrend>
) {
    Card(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (chartView == "pie") "薪资构成" else "月薪趋势",
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Row(Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))) {
                    listOf("pie" to "构成", "trend" to "趋势").forEach { (key, label) ->
                        Box(
                            Modifier
                                .background(if (chartView == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                                .clickable { onChange(key) }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(label, fontSize = 12.sp,
                                color = if (chartView == key) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (chartView == "pie") {
                SalaryPieChart(actual)
            } else {
                SalaryTrendBar(trend)
            }
        }
    }
}

@Composable
private fun SalaryPieChart(s: SalarySummary) {
    val total = s.baseSalary + s.basePerformance + s.normalSalary + s.overtimeSalary + s.weekendSalary + s.holidaySalary + s.totalSubsidy
    if (total <= 0) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text("暂无薪资数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val segments = listOf(
        Triple("底薪", s.baseSalary, Color(0xFF059669)),
        Triple("绩效", s.basePerformance, Color(0xFF3B82F6)),
        Triple("正常", s.normalSalary, Color(0xFF10B981)),
        Triple("加班", s.overtimeSalary, Color(0xFFDC2626)),
        Triple("周末", s.weekendSalary, Color(0xFFF59E0B)),
        Triple("节假日", s.holidaySalary, Color(0xFF7C3AED)),
        Triple("补贴", s.totalSubsidy, Color(0xFF06B6D4))
    ).filter { it.second > 0 }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // 扇形图
        androidx.compose.foundation.Canvas(Modifier.size(110.dp)) {
            val cx = size.width / 2; val cy = size.height / 2; val r = size.minDimension / 2 * 0.95f
            var startAngle = -90f
            segments.forEach { (_, v, color) ->
                val sweep = (v / total * 360f).toFloat()
                drawArc(color, startAngle, sweep, useCenter = true,
                    topLeft = Offset(cx - r, cy - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2))
                startAngle += sweep
            }
            // 中心白圆（donut 效果）
            drawCircle(androidx.compose.ui.graphics.Color.White, r * 0.55f, Offset(cx, cy))
        }
        // 图例
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            segments.forEach { (label, v, color) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
                    Text("$label ¥${fmtY(v)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text("(${(v / total * 100).toInt()}%)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SalaryTrendBar(trend: List<MonthlySalaryTrend>) {
    val maxV = trend.maxOf { it.value }.coerceAtLeast(1.0)
    Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
        trend.forEach { d ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(if (d.value > 0) "¥${(d.value / 100).toInt()}百" else "",
                    fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.height(14.dp))
                val barH = max(if (d.value > 0) 8f else 3f, (d.value / maxV * 72).toFloat())
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth(0.7f).height(barH.dp)) {
                    drawRect(if (d.value > 0) Color(0xFF059669) else Color(0xFFE5E7EB))
                }
                Spacer(Modifier.height(3.dp))
                Text(d.label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SalaryDailyRow(d: DayScheduleDetail) {
    Surface(
        Modifier.padding(horizontal = 12.dp, vertical = 3.dp).fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${d.date.substring(8)}日",
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.width(40.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                d.shift?.let {
                    val bgColor = runCatching { Color(it.color.toColorInt()) }.getOrElse { Color(0xFF059669) }
                    Surface(shape = RoundedCornerShape(4.dp), color = bgColor) {
                        Text(it.name, fontSize = 12.sp, color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                when (d.record?.type) {
                    ScheduleType.LEAVE -> {
                        val c = safeColor(BUILTIN_SHIFTS.firstOrNull { it.builtInType == "leave" }?.color ?: "#F43F5E")
                        SalaryBadge("请假", c.copy(alpha = 0.15f), c)
                    }
                    ScheduleType.SWAP  -> {
                        val c = safeColor(BUILTIN_SHIFTS.firstOrNull { it.builtInType == "swap" }?.color ?: "#78716C")
                        SalaryBadge("调休", c.copy(alpha = 0.15f), c)
                    }
                    ScheduleType.REST  -> {
                        val c = safeColor(BUILTIN_SHIFTS.firstOrNull { it.builtInType == "rest" }?.color ?: "#6B7280")
                        SalaryBadge("休息", c.copy(alpha = 0.15f), c)
                    }
                    else -> {}
                }
                if (d.extras.isNotEmpty()) {
                    SalaryBadge("+${d.extras.size}项", Color(0xFFECFDF5), Color(0xFF059669))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (d.salary > 0) {
                    Text("¥${fmtY(d.salary)}", fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (d.overtimeSalary > 0) {
                    Text("+¥${fmtY(d.overtimeSalary)}加班", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// DayScheduleDetail 有 salary 字段但没有 overtimeSalary，需要从 normalSalary 推算
// 这里 salary = 已在 CalcUtils 中计算的当日薪资总计，overtimeSalary 用 overtimeHours * 一个时率估算
private val DayScheduleDetail.overtimeSalary: Double
    get() = if (overtimeHours > 0 && salary > 0) {
        val totalH = normalHours + overtimeHours + weekendHours + holidayHours
        if (totalH > 0) salary * (overtimeHours / totalH) else 0.0
    } else 0.0

@Composable
private fun SalaryBadge(label: String, bgColor: Color, textColor: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = bgColor) {
        Text(label, fontSize = 11.sp, color = textColor,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
    }
}
