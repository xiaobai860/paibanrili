// app/src/main/java/com/schedulecalendar/app/ui/hours/HoursScreen.kt
package com.schedulecalendar.app.ui.hours

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.core.graphics.toColorInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.domain.model.DayScheduleDetail
import com.schedulecalendar.app.domain.model.HoursSummary
import com.schedulecalendar.app.domain.model.BUILTIN_SHIFTS
import com.schedulecalendar.app.domain.model.ScheduleType
import com.schedulecalendar.app.ui.component.MonthNavigator
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import com.schedulecalendar.app.ui.detail.safeColor
import com.schedulecalendar.app.ui.navigation.*
import java.time.LocalDate
import kotlin.math.max

/** 格式化工时：整数去尾零 8.0→"8"，8.5→"8.5" */
private fun fmtH(n: Double): String {
    val r = (Math.round(n * 10).toDouble()) / 10.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else "%.1f".format(r)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoursScreen(navController: NavController, vm: HoursViewModel = hiltViewModel()) {
    HoursContent(
        navController = navController,
        sharedYear = null,
        sharedMonth = null,
        onMonthChange = { _, _ -> },
        vm = vm
    )
}

/** 嵌入式内容：由 StatisticsScreen 传入共享月份 */
@Composable
fun HoursContent(
    navController: NavController,
    sharedYear: Int?,
    sharedMonth: Int?,
    modifier: Modifier = Modifier,
    onMonthChange: (Int, Int) -> Unit = { _, _ -> },
    vm: HoursViewModel = hiltViewModel()
) {
    val isEmbedded = sharedYear != null

    // 仅在非嵌入模式下监听生命周期 onResume 刷新（嵌入模式由 Pager 管理，避免不必要的 reload）
    if (!isEmbedded) {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) vm.reload()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    // 外部共享月份同步（单向：父→子）
    LaunchedEffect(sharedYear, sharedMonth) {
        if (sharedYear != null && sharedMonth != null) {
            vm.goToMonth(sharedYear, sharedMonth)
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.uiEvent.collect { event ->
            when (event) {
                is HoursUiEvent.NavigateToDetail ->
                    navController.navigate(RouteHoursDetail(event.year, event.month, event.type))
                is HoursUiEvent.ShowError -> snackbar.showSnackbar(event.message)
            }
        }
    }

    var chartView by remember { mutableStateOf("daily") }

    val contentBlock: @Composable (PaddingValues) -> Unit = { padding ->
        if (state.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val actual = state.actual
            val future = state.future
            val details = state.details
            val attendCfg = state.attendConfig

            val todayStr = java.time.LocalDate.now().let { "%04d-%02d-%02d".format(it.year, it.monthValue, it.dayOfMonth) }
            val workDays = details.filter { it.record != null && it.date <= todayStr }.reversed()

            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // ── 统计卡片 2列 ──────────────────────────────────────────
                item {
                    HoursStatsGrid(actual, future, attendCfg,
                        onLateClick  = { if (actual.lateCount > 0) vm.navigateToDetail("late") },
                        onEarlyClick = { if (actual.earlyLeaveCount > 0) vm.navigateToDetail("early") },
                        onRemarkClick = { vm.navigateToDetail("remark") },
                        onExtraClick  = { vm.navigateToDetail("extra") },
                        details = details,
                    )
                }

                // ── 总工时汇总 ────────────────────────────────────────────
                item {
                    Surface(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("本月总工时", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f), fontSize = 14.sp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${fmtH(actual.totalHours)}h",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                val futureTotal = future?.let { it.normalHours + it.overtimeHours + it.weekendHours + it.holidayHours } ?: 0.0
                                if (futureTotal > 0.0) {
                                    Text("预计+${fmtH(futureTotal)}h",
                                        color = Color(0xFF86EFAC), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // ── 图表 ──────────────────────────────────────────────────
                if (state.recentDetails.any { it.record != null } || details.any { it.record != null }) {
                    item {
                        HoursChartCard(
                            chartView = chartView,
                            onChartViewChange = { chartView = it },
                            recentDetails = state.recentDetails,
                            trend = state.trend
                        )
                    }
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

                if (workDays.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("本月未设置排班", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(workDays, key = { it.date }) { d ->
                        HoursDailyRow(d)
                    }
                }
            }
        }
    }

    if (isEmbedded) {
        // 嵌入模式：直接渲染内容（顶部导航由 StatisticsScreen 提供）
        contentBlock(PaddingValues(0.dp))
    } else {
        // 独立模式：使用自带 Scaffold
        Scaffold(
            topBar = {
                ScheduleTopBar("工时记录", actions = {
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
private fun HoursStatsGrid(
    actual: HoursSummary,
    future: HoursSummary?,
    attendCfg: com.schedulecalendar.app.domain.model.AttendConfig,
    onLateClick: () -> Unit, onEarlyClick: () -> Unit,
    onRemarkClick: () -> Unit, onExtraClick: () -> Unit,
    details: List<DayScheduleDetail>
) {
    val todayStr = remember {
        LocalDate.now().let { "%04d-%02d-%02d".format(it.year, it.monthValue, it.dayOfMonth) }
    }
    val remarkCount = remember(details, todayStr) {
        details.count { d -> !d.record?.remark.isNullOrBlank() && d.date <= todayStr }
    }
    val extraCount = remember(details, todayStr) {
        details.count { d -> d.extras.isNotEmpty() && d.date <= todayStr }
    }

    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 行1：正常 + 加班
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoursStatCell(label = "正常工时",
                value  = "${fmtH(actual.normalHours)}h",
                future = future?.normalHours?.takeIf { it > 0 }?.let { "预计+${fmtH(it)}h" },
                modifier = Modifier.weight(1f))
            HoursStatCell(label = "加班工时",
                value  = "${fmtH(actual.overtimeHours)}h",
                future = future?.overtimeHours?.takeIf { it > 0 }?.let { "预计+${fmtH(it)}h" },
                valueColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f))
        }
        // 行2：周末 + 节假日
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoursStatCell(label = "周末工时",
                value  = "${fmtH(actual.weekendHours)}h",
                future = future?.weekendHours?.takeIf { it > 0 }?.let { "预计+${fmtH(it)}h" },
                modifier = Modifier.weight(1f))
            HoursStatCell(label = "节假日工时",
                value  = "${fmtH(actual.holidayHours)}h",
                future = future?.holidayHours?.takeIf { it > 0 }?.let { "预计+${fmtH(it)}h" },
                modifier = Modifier.weight(1f))
        }
        // 行3：请假 + 调休/休息
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoursStatCell(label = "请假天数",
                value  = run {
                    val days = actual.leaveDays
                    val hrs  = actual.leaveHoursRemainder
                    if (hrs > 0) "${days}天${fmtH(hrs)}h" else "${days}天"
                },
                future = future?.let {
                    val days = it.leaveDays; val hrs = it.leaveHoursRemainder
                    if (days > 0 || hrs > 0) {
                        if (hrs > 0) "预计${days}天${fmtH(hrs)}h" else "预计${days}天"
                    } else null
                },
                modifier = Modifier.weight(1f))
            HoursStatCell(label = "调休/休息",
                value  = "${actual.swapDays + actual.restDays}天",
                future = future?.let { it.swapDays + it.restDays }?.takeIf { it > 0 }?.let { "预计${it}天" },
                modifier = Modifier.weight(1f))
        }
        // 行4：迟到次数（可点击）+ 早退次数（可点击）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val lateAlert = actual.lateCount >= attendCfg.lateAlertCount && actual.lateCount > 0
            val earlyAlert = actual.earlyLeaveCount >= attendCfg.earlyLeaveAlertCount && actual.earlyLeaveCount > 0
            HoursStatCell(label = "迟到次数",
                value  = "${actual.lateCount}次",
                valueColor = if (lateAlert) Color(0xFFF97316) else if (actual.lateCount > 0) MaterialTheme.colorScheme.error else null,
                alertBadge = lateAlert,
                clickable = actual.lateCount > 0,
                onClick = onLateClick,
                modifier = Modifier.weight(1f))
            HoursStatCell(label = "早退次数",
                value  = "${actual.earlyLeaveCount}次",
                valueColor = if (earlyAlert) Color(0xFFF97316) else if (actual.earlyLeaveCount > 0) MaterialTheme.colorScheme.error else null,
                alertBadge = earlyAlert,
                clickable = actual.earlyLeaveCount > 0,
                onClick = onEarlyClick,
                modifier = Modifier.weight(1f))
        }
        // 行5：本月备注 + 补贴/扣款
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoursStatCell(label = "本月备注",
                value = "${remarkCount}条",
                clickable = remarkCount > 0,
                onClick = onRemarkClick,
                modifier = Modifier.weight(1f))
            HoursStatCell(label = "补贴/扣款",
                value = "${extraCount}天",
                clickable = extraCount > 0,
                onClick = onExtraClick,
                modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun HoursStatCell(
    label: String, value: String,
    modifier: Modifier = Modifier,
    future: String? = null,
    valueColor: Color? = null,
    alertBadge: Boolean = false,
    clickable: Boolean = false,
    onClick: () -> Unit = {}
) {
    Surface(
        modifier = modifier
            .height(72.dp)           // 固定卡片高度，避免 alertBadge/future/clickable 切换导致高度抖动
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(12.dp),
        color = if (alertBadge) Color(0xFFFFF7ED) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (alertBadge) Color(0xFFFED7AA) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = valueColor ?: MaterialTheme.colorScheme.onSurface)
                    if (alertBadge) {
                        Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFFEF3C7)) {
                            Text("超限", fontSize = 10.sp, color = Color(0xFFF97316),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                    if (future != null) {
                        Text(future, fontSize = 11.sp, color = Color(0xFF16A34A))
                    }
                }
                Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (clickable) {
                Icon(Icons.Filled.ChevronRight, null,
                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HoursChartCard(
    chartView: String, onChartViewChange: (String) -> Unit,
    recentDetails: List<DayScheduleDetail>,
    trend: List<MonthlyHoursTrend>
) {
    Card(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            // 标题 + 切换
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (chartView == "daily") "每日工时分布" else "月工时分布",
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Row(Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))) {
                    listOf("daily" to "每日", "monthly" to "月工时").forEach { (key, label) ->
                        Box(
                            Modifier
                                .background(if (chartView == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                                .clickable { onChartViewChange(key) }
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

            // 统一动态高度：根据数据最大值动态计算，取两图表的较大值
            val topLabelH = 16.dp
            val bottomLabelH = 16.dp
            val minBarH = 60.dp      // 柱体区最小高度
            val maxBarH = 140.dp     // 柱体区最大高度
            val refDailyMax = 8.0    // 每日参考满刻度（8小时）
            val refMonthlyMax = 200.0 // 月度参考满刻度（200小时）
            val dailyMax = remember(recentDetails) {
                if (recentDetails.isEmpty()) 1.0 else recentDetails.maxOf { it.normalHours + it.overtimeHours }.coerceAtLeast(1.0)
            }
            val monthlyMax = remember(trend) {
                if (trend.isEmpty()) 1.0 else trend.maxOf { it.total }.coerceAtLeast(1.0)
            }
            val dailyChartH = remember(dailyMax) {
                val barArea = (minBarH + (maxBarH - minBarH) * (dailyMax / refDailyMax).coerceIn(0.3, 1.0).toFloat())
                topLabelH + bottomLabelH + barArea
            }
            val monthlyChartH = remember(monthlyMax) {
                val barArea = (minBarH + (maxBarH - minBarH) * (monthlyMax / refMonthlyMax).coerceIn(0.3, 1.0).toFloat())
                topLabelH + bottomLabelH + barArea
            }
            val unifiedChartH = remember(dailyChartH, monthlyChartH) {
                maxOf(dailyChartH, monthlyChartH)
            }

            // 共享图例：日工时 / 月工时图表使用同一组颜色 + 文字标注（确保图例统一）
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Color(0xFF059669) to "正常", Color(0xFFDC2626) to "加班(含周末/法定)").forEach { (c, l) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(8.dp).background(c, RoundedCornerShape(2.dp)))
                        Text(l, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            if (chartView == "daily") {
                DailyHoursBar(recentDetails)
            } else {
                MonthlyHoursBar(trend)
            }
        }
    }
}

@Composable
private fun DailyHoursBar(details: List<DayScheduleDetail>) {
    // 固定显示最近7天（不受选中月份影响，可跨月）
    val chartDays = remember(details) {
        (6 downTo 0).mapNotNull { offset ->
            val d  = LocalDate.now().minusDays(offset.toLong())
            val ds = "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
            details.firstOrNull { it.date == ds } ?: DayScheduleDetail(date = ds)
        }
    }
    val maxH = chartDays.maxOf { it.normalHours + it.overtimeHours }.coerceAtLeast(1.0)
    val textMeasurer = rememberTextMeasurer()

    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        val n = chartDays.size
        val colW = size.width / n
        val barW = colW * 0.6f
        val topLabelPx = 14.dp.toPx()
        val bottomDatePx = 14.dp.toPx()
        val barMaxH = size.height - topLabelPx - bottomDatePx
        chartDays.forEachIndexed { i, d ->
            val total = d.normalHours + d.overtimeHours
            val cx = colW * i + colW / 2
            val barH = if (total > 0) max(8f, (total / maxH * barMaxH).toFloat()) else 3f
            val barLeft = cx - barW / 2
            val barTop = size.height - bottomDatePx - barH
            val segs = listOf(
                d.normalHours to Color(0xFF059669),
                d.overtimeHours to Color(0xFFDC2626)
            ).filter { it.first > 0 }
            if (segs.isEmpty()) {
                drawRect(Color(0xFFE5E7EB), topLeft = Offset(barLeft, barTop),
                    size = androidx.compose.ui.geometry.Size(barW, barH))
            } else {
                var y = barTop + barH
                segs.forEach { (h, color) ->
                    val sh = (h / total * barH).toFloat()
                    y -= sh
                    drawRect(color, topLeft = Offset(barLeft, y),
                        size = androidx.compose.ui.geometry.Size(barW, sh))
                }
            }
            // top label
            val topLabel: String
            val topColor: Color
            when {
                d.holidayHours > 0 -> { topLabel = "法${fmtH(d.holidayHours)}h"; topColor = Color(0xFFD97706) }
                d.weekendHours > 0 -> { topLabel = "末${fmtH(d.weekendHours)}h"; topColor = Color(0xFFD97706) }
                else -> { topLabel = if (total > 0) "${fmtH(total)}h" else ""; topColor = Color(0xFF78909C) }
            }
            if (topLabel.isNotEmpty()) {
                val tl = textMeasurer.measure(topLabel,
                    androidx.compose.ui.text.TextStyle(fontSize = 8.sp, color = topColor))
                // 顶部时长文案位置 = 柱顶上方紧贴 1dp，跟随柱体高度上下变化（不再固定在 y=0）
                drawText(tl, topLeft = Offset(cx - tl.size.width / 2f, barTop - tl.size.height - 1.dp.toPx()))
            }
            // bottom date label
            val dl = textMeasurer.measure(d.date.substring(8),
                androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = Color(0xFF78909C)))
            drawText(dl, topLeft = Offset(cx - dl.size.width / 2f, size.height - bottomDatePx + (bottomDatePx - dl.size.height) / 2f))
        }
    }
}

@Composable
private fun MonthlyHoursBar(trend: List<MonthlyHoursTrend>) {
    val maxH = trend.maxOf { it.total }.coerceAtLeast(1.0)
    val textMeasurer = rememberTextMeasurer()

    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        val n = trend.size
        val colW = size.width / n
        val barW = colW * 0.6f
        val topLabelPx = 14.dp.toPx()
        val bottomLabelPx = 14.dp.toPx()
        val barMaxH = size.height - topLabelPx - bottomLabelPx
        trend.forEachIndexed { i, d ->
            val cx = colW * i + colW / 2
            val barH = if (d.total > 0) max(8f, (d.total / maxH * barMaxH).toFloat()) else 3f
            val barLeft = cx - barW / 2
            val barTop = size.height - bottomLabelPx - barH
            if (d.total == 0.0) {
                drawRect(Color(0xFFE5E7EB), topLeft = Offset(barLeft, barTop),
                    size = androidx.compose.ui.geometry.Size(barW, barH))
            } else {
                val normPct = (d.normal / d.total).toFloat()
                val otPct = (d.overtime / d.total).toFloat()
                drawRect(Color(0xFF059669), topLeft = Offset(barLeft, barTop + barH * otPct),
                    size = androidx.compose.ui.geometry.Size(barW, barH * normPct))
                if (d.overtime > 0)
                    drawRect(Color(0xFFDC2626), topLeft = Offset(barLeft, barTop),
                        size = androidx.compose.ui.geometry.Size(barW, barH * otPct))
            }
            // top value label — 跟随柱体高度：柱顶上方紧贴 1dp
            if (d.total > 0) {
                val tl = textMeasurer.measure("${fmtH(d.total)}h",
                    androidx.compose.ui.text.TextStyle(fontSize = 8.sp, color = Color(0xFF78909C)))
                drawText(tl, topLeft = Offset(cx - tl.size.width / 2f, barTop - tl.size.height - 1.dp.toPx()))
            }
            // bottom month label
            val dl = textMeasurer.measure(d.label,
                androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = Color(0xFF78909C)))
            drawText(dl, topLeft = Offset(cx - dl.size.width / 2f, size.height - bottomLabelPx + (bottomLabelPx - dl.size.height) / 2f))
        }
    }
}

@Composable
private fun HoursDailyRow(d: DayScheduleDetail) {
    // 注意：overtimeHours 已包含 weekend + holiday，不可重复相加
    val totalH = d.normalHours + d.overtimeHours
    Surface(
        Modifier.padding(horizontal = 12.dp, vertical = 3.dp).fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${d.date.substring(8)}日",
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(40.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    d.shift?.let {
                        val bgColor = runCatching { Color(it.color.toColorInt()) }.getOrElse { Color(0xFF059669) }
                        Surface(shape = RoundedCornerShape(4.dp), color = bgColor) {
                            Text(it.name, fontSize = 12.sp, color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (d.holidayHours > 0) ShiftTypeBadge("节假日", Color(0xFFEDE9FE), Color(0xFF6D28D9))
                    else if (d.weekendHours > 0) ShiftTypeBadge("周末", Color(0xFFFEF3C7), Color(0xFFD97706))
                    when (d.record?.type) {
                        com.schedulecalendar.app.domain.model.ScheduleType.LEAVE ->
                            ShiftTypeBadge("请假", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
                        com.schedulecalendar.app.domain.model.ScheduleType.SWAP  ->
                            ShiftTypeBadge("调休", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
                        com.schedulecalendar.app.domain.model.ScheduleType.REST  ->
                            ShiftTypeBadge("休息", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> {}
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (totalH > 0) Text("${fmtH(totalH)}h", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (d.overtimeHours > 0) Text("+${fmtH(d.overtimeHours)}h加班",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            val rec = d.record
            if (rec != null && (!rec.actualStartTime.isNullOrEmpty() || !rec.actualEndTime.isNullOrEmpty() || !rec.remark.isNullOrBlank())) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rec.actualStartTime?.takeIf { it.isNotEmpty() }?.let { t ->
                        Text("↑$t", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    rec.actualEndTime?.takeIf { it.isNotEmpty() }?.let { t ->
                        Text("↓$t", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    rec.remark?.takeIf { it.isNotBlank() }?.let { remark ->
                        Text("备注：$remark", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShiftTypeBadge(label: String, bgColor: Color, textColor: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = bgColor) {
        Text(label, fontSize = 11.sp, color = textColor,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
    }
}
