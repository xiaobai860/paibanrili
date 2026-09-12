// app/src/main/java/com/schedulecalendar/app/ui/calendar/CalendarCells.kt
package com.schedulecalendar.app.ui.calendar


import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schedulecalendar.app.domain.model.*
import com.schedulecalendar.app.ui.detail.safeColor
import com.schedulecalendar.app.ui.navigation.*
import com.schedulecalendar.app.ui.theme.EarlyLeaveOrange
import com.schedulecalendar.app.ui.theme.Green100
import com.schedulecalendar.app.ui.theme.Green700
import com.schedulecalendar.app.ui.theme.HolidayRed
import com.schedulecalendar.app.ui.theme.RedError
import com.schedulecalendar.app.ui.theme.RemarkCyan
import java.time.LocalDate

// ════════════════════════════════════════════════════════════════════════════
// 日历网格 DayCell
// ════════════════════════════════════════════════════════════════════════════

/**
 * 状态迷你角标（早/迟/注）
 * 显示在日期数字左侧，垂直排列
 */
@Composable
internal fun StatusMiniBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(1.5.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            fontSize = 7.sp,
            lineHeight = 7.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 1.5.dp, vertical = 0.dp)
        )
    }
}

/** DayCell 底部行类型 */

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DayCell(
    day: Int, dateStr: String,
    shift: Shift?, record: ScheduleRecord?, detail: DayScheduleDetail?,
    isToday: Boolean, isHoliday: Boolean, isWeekend: Boolean,
    displayScheme: DisplayScheme,
    shiftStatuses: List<ShiftStatus>,
    batchMode: Boolean, selected: Boolean,
    modifier: Modifier = Modifier,
    hasCalendarEvent: Boolean = false,
    isPrevMonth: Boolean = false, isNextMonth: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val shiftColor = shift?.color?.let { safeColor(it) }
    val isRest = shift?.builtInType == "rest" || shift?.builtInType == "swap"

    // ── 早退/迟到/备注状态检测 ───────────────────────────────────
    // 迟到：与工时报表（calcMonthHours）逻辑一致，简单分钟比较
    val isLate = record?.actualStartTime != null && shift?.startTime != null &&
        record.actualStartTime.isNotBlank() && shift.startTime.isNotBlank() &&
        CalcUtils.timeToMin(record.actualStartTime) > CalcUtils.timeToMin(shift.startTime)
    // 早退：使用 normRange 支持跨天比较，与 calcMonthHours 一致
    val isEarlyLeave = record?.actualEndTime != null && shift?.endTime != null &&
        record.actualEndTime.isNotBlank() && shift.endTime.isNotBlank() &&
        shift.startTime.isNotBlank() &&
        run {
            val sS = CalcUtils.timeToMin(shift.startTime)
            val (_, normSE) = CalcUtils.normRange(sS, CalcUtils.timeToMin(shift.endTime))
            val (_, normAE) = CalcUtils.normRange(sS, CalcUtils.timeToMin(record.actualEndTime))
            normSE - normAE > 0
        }
    val hasRemark = !record?.remark.isNullOrBlank()
    // 构建左侧状态角标列表
    val statusBadges = buildList<@Composable () -> Unit> {
        if (isEarlyLeave) add({ StatusMiniBadge("早", EarlyLeaveOrange) })
        if (isLate) add({ StatusMiniBadge("迟", RedError) })
        if (hasRemark) add({ StatusMiniBadge("注", RemarkCyan) })
    }

    // ── 视觉状态 ──────────────────────────────────────────────
    val interactionSource = remember { MutableInteractionSource() }
    val cellBg = when {
        selected -> HolidayRed.copy(alpha = 0.08f)          // 选中：浅红色填充（优先级最高）
        isToday -> Green100                                          // 今天：浅绿色填充
        shiftColor != null && !isRest -> shiftColor.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val cellTextFg = when {
        selected -> HolidayRed   // 选中：深红色文字（优先级最高）
        isToday -> Green700          // 今天：深绿色文字
        isHoliday -> HolidayRed
        isWeekend -> HolidayRed.copy(alpha = 0.8f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val lunarTextFg = when {
        selected -> HolidayRed.copy(alpha = 0.7f)   // 选中：浅红文字（优先级最高）
        isToday -> Green700.copy(alpha = 0.8f)   // 今天：浅绿文字
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val isHighlighted = isToday || selected
    val cellShape = RoundedCornerShape(4.dp)
    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    // ── 无障碍描述 ──────────────────────────────────────────────
    // dateParts/lunarText 仅依赖 dateStr，用 remember 缓存，避免每次重组重复解析+农历换算
    val dateParts = dateStr.split("-")
    val lunarText = remember(dateStr) {
        val y = dateParts.getOrNull(0)?.toIntOrNull() ?: LocalDate.now().year
        val m = dateParts.getOrNull(1)?.toIntOrNull() ?: LocalDate.now().monthValue
        val d = dateParts.getOrNull(2)?.toIntOrNull() ?: LocalDate.now().dayOfMonth
        LunarCalendar.getLunarDayText(y, m, d)
    }
    val shiftText = shift?.name ?: "无班次"
    val accessibilityDescription = buildString {
        val m = dateParts.getOrNull(1)?.toIntOrNull() ?: LocalDate.now().monthValue
        append("${m}月${day}日，$shiftText，$lunarText")
        if (isToday) append("，今天")
        if (isHoliday) append("，节假日")
        if (isWeekend) append("，周末")
        record?.let { if (it.actualStartTime != null) append("，已打卡") }
    }

    // ── 农历/节假日名称 ──────────────────────────────────────────
    val holidayName = remember(dateStr, isHoliday) {
        if (isHoliday) HolidayData.getHolidayName(dateStr) else null
    }
    val isMakeupDay = remember(dateStr) { HolidayData.isMakeupDay(dateStr) }
    val badgeText = when {
        isHoliday -> "休"; isMakeupDay -> "补"; else -> null
    }
    // 判断是否为法定节假日的第一天（用于农历行显示节日名）
    val isHolidayFirstDay = remember(dateStr, isHoliday) {
        if (isHoliday) {
            val prevDate = try {
                val p = java.time.LocalDate.parse(dateStr).minusDays(1)
                "%04d-%02d-%02d".format(p.year, p.monthValue, p.dayOfMonth)
            } catch (_: Exception) { null }
            prevDate == null || !HolidayData.isLegalHoliday(prevDate)
        } else false
    }

    // ── 农历行显示内容（按优先级）──────────────────────────────
    // 1. 法定节假日名称（最高优先级，保持现有逻辑）
    // 2. 二十四节气名称
    // 3. 传统民俗节日名称
    // 4. 官方纪念日名称
    // 5. 热门国际节假日名称
    // 6. 普通农历日期（最低优先级）
    val festivalInfo = remember(dateStr) { HolidayData.getFullFestivalInfo(dateStr) }
    val lunarDisplayText = when {
        isHolidayFirstDay && holidayName != null -> holidayName
        festivalInfo.isNotEmpty() -> festivalInfo.first()
        else -> lunarText
    }

    // ── 班次/状态标签颜色 ──────────────────────────────────────
    val appliedSt = record?.appliedStatus?.let { ast -> shiftStatuses.find { it.id == ast.statusId } }
    // 仅当方案数据行中配置了 SHIFT/STATUS 时才显示对应标签
    val schemeHasShiftItem = displayScheme.dataRows.any { row ->
        row.items.filterNotNull().any { it == DisplayItemType.SHIFT }
    }
    val schemeHasStatusItem = displayScheme.dataRows.any { row ->
        row.items.filterNotNull().any { it == DisplayItemType.STATUS }
    }
    val hasShift = shift != null && schemeHasShiftItem
    val hasStatus = appliedSt != null && schemeHasStatusItem
    
    // 获取三行数据行配置
    val dataRows = if (!displayScheme.isNoScheme) {
        displayScheme.dataRows.take(3)
    } else emptyList()

    // ── 数据项文本计算 ──────────────────────────────────────────
    fun calcItemText(type: DisplayItemType): String = when (type) {
        DisplayItemType.TOTAL_HOURS -> "${CalcUtils.fmtHours((detail?.normalHours ?: 0.0) + (detail?.overtimeHours ?: 0.0))}h"
        DisplayItemType.WORK_HOURS -> "${CalcUtils.fmtHours(detail?.normalHours ?: 0.0)}h"
        DisplayItemType.OVERTIME_HOURS -> "${CalcUtils.fmtHours(detail?.overtimeHours ?: 0.0)}h"
        DisplayItemType.DAILY_INCOME -> { val s = detail?.salary ?: 0.0; if (s > 0) "\u00a5${CalcUtils.fmtMoney(s)}" else "\u00a50" }
        DisplayItemType.NORMAL_INCOME -> {
            val ns = detail?.normalSalary ?: 0.0
            if (ns > 0) "\u00a5${CalcUtils.fmtMoney(ns)}" else "\u00a50"
        }
        DisplayItemType.OVERTIME_INCOME -> {
            val os = detail?.overtimeSalary ?: 0.0
            if (os > 0) "\u00a5${CalcUtils.fmtMoney(os)}" else "\u00a50"
        }
        DisplayItemType.SHIFT -> shift?.name ?: ""
        DisplayItemType.STATUS -> appliedSt?.name ?: ""
    }

    // ── 根据背景色亮度自动计算对比文字色（考虑 alpha 与白色混合） ──
    fun textColorForBg(bgColor: Color?): Color {
        if (bgColor == null) return Color(0xFF1A1A1A)
        val alpha = 0.2f
        val r = ((bgColor.red * alpha + 1.0f * (1 - alpha)) * 255).toInt().coerceIn(0, 255)
        val g = ((bgColor.green * alpha + 1.0f * (1 - alpha)) * 255).toInt().coerceIn(0, 255)
        val b = ((bgColor.blue * alpha + 1.0f * (1 - alpha)) * 255).toInt().coerceIn(0, 255)
        val brightness = (r * 299 + g * 587 + b * 114) / 1000
        return if (brightness > 128) Color(0xFF1A1A1A) else Color(0xFFF5F5F5)
    }

    // ── 布局常量 ──────────────────────────────────────────────
    val dateHeight = 28.dp; val lunarGap = 2.dp; val lunarHeight = 12.dp
    val dataGap = 3.dp; val dataRowHeight = 12.dp; val dataRowGap = 1.dp
    val rowTextSize = 10.sp; val lunarTextSize = MaterialTheme.typography.labelSmall.fontSize

    // ── 外层 Box（圆角+背景+边框）───────────────────────────
    val cellAlpha = if (isPrevMonth) 0.4f else if (isNextMonth) 0.45f else 1f
    Box(
        modifier
            .fillMaxWidth()
            .clip(cellShape)
            .drawWithContent {
                val cr = CornerRadius(4.dp.toPx())
                drawRoundRect(color = cellBg.copy(alpha = cellBg.alpha * cellAlpha), cornerRadius = cr)
                drawContent()
                drawRoundRect(color = outlineColor.copy(alpha = outlineColor.alpha * cellAlpha), cornerRadius = cr, style = Stroke(width = 0.5.dp.toPx()))
                when {
                    selected -> drawRoundRect(color = HolidayRed, cornerRadius = cr, style = Stroke(width = 2.5.dp.toPx()))
                    isToday -> drawRoundRect(color = Green700, cornerRadius = cr, style = Stroke(width = 2.5.dp.toPx()))
                }
            }
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            }
            .semantics { contentDescription = accessibilityDescription }
            .padding(horizontal = 0.5.dp),
        contentAlignment = Alignment.Center
    ) {
        // 内部 Column：填满父容器，底部留6dp安全区
        Column(
            Modifier.fillMaxWidth().alpha(cellAlpha),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // ── 1. 日期区域（含状态角标+事件指示点）───────────────
            Box(Modifier.fillMaxWidth().height(dateHeight)) {
                // 日期数字 Box（28dp 固定高度）
                Box(Modifier.fillMaxWidth().height(dateHeight), contentAlignment = Alignment.TopCenter) {
                    Text(
                        day.toString(), fontSize = 20.sp, lineHeight = 20.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                        fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Medium,
                        color = cellTextFg
                    )
                    // 右上角 假/补 角标
                    if (badgeText != null) {
                        val isRestBadge = isHoliday
                        val badgeBg = if (isRestBadge) Green700.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                        val badgeFg = if (isRestBadge) Green700 else MaterialTheme.colorScheme.error
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = badgeBg,
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Text(badgeText, fontSize = lunarTextSize, lineHeight = lunarTextSize,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                fontWeight = FontWeight.Bold, color = badgeFg,
                                modifier = Modifier.padding(horizontal = 2.dp, vertical = 0.dp))
                        }
                    }
                    // 底部事件指示点（日程/纪念日）
                    if (hasCalendarEvent) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .size(3.5.dp)
                                .background(
                                    Green700,
                                    CircleShape
                                )
                        )
                    }
                }
                // 左侧状态角标（早退/迟到/备注）——允许向下溢出日期区域
                if (statusBadges.isNotEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.TopStart),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        statusBadges.forEach { badge -> badge() }
                    }
                }
            }
            // ── 2. 农历间距 2dp ──────────────────────────
            Spacer(Modifier.height(lunarGap))
            // ── 3. 农历文字（12dp）─────────────────────────
            Box(Modifier.fillMaxWidth().height(lunarHeight), contentAlignment = Alignment.Center) {
                val lunarColor = lunarTextFg
                Text(lunarDisplayText, fontSize = lunarTextSize, lineHeight = lunarTextSize,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    color = if (isHolidayFirstDay && holidayName != null) HolidayRed else lunarColor,
                    maxLines = 1, overflow = TextOverflow.Clip)
            }
            // ── 4. 农历→数据行间距 3dp ──────────────────
            Spacer(Modifier.height(dataGap))
            // ── 5. 数据行区域（置底，空行在上）───────────────
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dataRowGap)) {
                // 构建内容行列表（严格按 dataRows 配置顺序，SHIFT/STATUS 保留在原始行）
                val contentRows = dataRows.mapIndexedNotNull { index, rowConfig ->
                    val visibleItems = rowConfig.items.filterNotNull().filter { item ->
                        when (item) {
                            DisplayItemType.SHIFT -> shift != null
                            DisplayItemType.STATUS -> appliedSt != null
                            else -> true
                        }
                    }
                    if (visibleItems.isNotEmpty()) Pair(true, index) else null
                }
                val emptyCount = (3 - contentRows.size).coerceAtLeast(0)
                // 空行上移，数据行（含 SHIFT/STATUS）下沉，维持配置顺序
                val allRows = List(emptyCount) { Pair(false, -1) } + contentRows

                allRows.forEachIndexed { idx, (hasRow, rowIndex) ->
                    if (hasRow) {
                        if (rowIndex == -1) {
                            // 班次/附加状态标签行（使用实际颜色背景 + 自动反色文字）
                            Row(Modifier.fillMaxWidth().height(dataRowHeight), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                if (hasShift) {
                                    val shiftBg = shiftColor?.copy(alpha = 0.2f)
                                        ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    val w = if (hasStatus) Modifier.weight(1f) else Modifier.fillMaxWidth()
                                    Surface(shape = RoundedCornerShape(2.dp), color = shiftBg, modifier = w) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(shift.name, fontSize = rowTextSize, lineHeight = rowTextSize,
                                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                                maxLines = 1, overflow = TextOverflow.Clip, fontWeight = FontWeight.Medium,
                                                color = textColorForBg(shiftColor),
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.wrapContentHeight(Alignment.CenterVertically))
                                        }
                                    }
                                }
                                if (hasStatus) {
                                    val statusColor = appliedSt.color.let { safeColor(it) }
                                    val statusBg = statusColor.copy(alpha = 0.2f)
                                    val w = if (hasShift) Modifier.weight(1f) else Modifier.fillMaxWidth()
                                    Surface(shape = RoundedCornerShape(2.dp), color = statusBg, modifier = w) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(appliedSt.name, fontSize = rowTextSize, lineHeight = rowTextSize,
                                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                                maxLines = 1, overflow = TextOverflow.Clip, fontWeight = FontWeight.Medium,
                                                color = textColorForBg(statusColor),
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.wrapContentHeight(Alignment.CenterVertically))
                                        }
                                    }
                                }
                            }
                        } else {
                            // 数据项行（支持每行最多2个数据项）
                            val rowConfig = dataRows.getOrNull(rowIndex)
                            if (rowConfig != null && rowConfig.items.any { it != null }) {
                                val visibleItems = rowConfig.items.filterNotNull().filter { item ->
                                    when (item) {
                                        DisplayItemType.SHIFT -> shift != null
                                        DisplayItemType.STATUS -> appliedSt != null
                                        else -> true
                                    }
                                }
                                if (visibleItems.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(dataRowHeight),
                                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    visibleItems.forEachIndexed { index, item ->
                                        // 根据数据项类型获取背景色和文字色：
                                        // SHIFT/STATUS 使用实际绑定颜色；其他类型使用用户自定义背景色
                                        val rowBgColor = when (item) {
                                            DisplayItemType.SHIFT -> shift?.color?.let { safeColor(it) }
                                            DisplayItemType.STATUS -> appliedSt?.color?.let { safeColor(it) }
                                            else -> if (index == 0) {
                                                rowConfig.backgroundColorLeft?.let {
                                                    try { Color(it.toColorInt()) }
                                                    catch (_: Exception) { null }
                                                }
                                            } else {
                                                rowConfig.backgroundColorRight?.let {
                                                    try { Color(it.toColorInt()) }
                                                    catch (_: Exception) { null }
                                                }
                                            }
                                        }
                                        // 根据背景色亮度自动计算对比文字色（深灰/浅灰）
                                        val rowTextColor = textColorForBg(rowBgColor)

                                        Surface(
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            shape = RoundedCornerShape(2.dp),
                                            color = rowBgColor?.copy(alpha = 0.2f) ?: when {
                                                selected -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                                isToday -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                            }
                                        ) {
                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text(
                                                    calcItemText(item),
                                                    fontSize = rowTextSize,
                                                    lineHeight = rowTextSize,
                                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Clip,
                                                    fontWeight = FontWeight.Medium,
                                                    color = rowTextColor,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.wrapContentHeight(Alignment.CenterVertically)
                                                )
                                            }
                                        }
                                    }
                                }
                                } else {
                                    // 过滤后无可见数据项，显示空行占位
                                    Spacer(Modifier.fillMaxWidth().height(dataRowHeight))
                                }
                            } else {
                                // 空行占位
                                Spacer(Modifier.fillMaxWidth().height(dataRowHeight))
                            }
                        }
                    } else {
                        // 空行占位
                        Spacer(Modifier.fillMaxWidth().height(dataRowHeight))
                    }
                }
            }
        }
    }
}
