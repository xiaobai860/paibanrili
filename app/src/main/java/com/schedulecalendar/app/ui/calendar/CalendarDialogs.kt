// app/src/main/java/com/schedulecalendar/app/ui/calendar/CalendarDialogs.kt
package com.schedulecalendar.app.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schedulecalendar.app.domain.model.LunarCalendar
import java.time.YearMonth

/**
 * 日历页的弹窗与模式工具栏集合
 *
 * 从 CalendarScreen.kt 拆出，避免单文件职责过载（原文件 >2400 行）。
 * 均为 internal：仅对本模块内 CalendarScreen 暴露。
 */

@Composable
internal fun BuiltinTag() {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    ) {
        Text(
            text  = "内置",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

@Composable
internal fun CopyMonthDialog(
    currentYear:  Int,
    currentMonth: Int,
    onConfirm:    (dstYear: Int, dstMonth: Int, overwrite: Boolean) -> Unit,
    onDismiss:    () -> Unit
) {
    val nextMonthYear  = if (currentMonth == 12) currentYear + 1 else currentYear
    val nextMonth      = if (currentMonth == 12) 1 else currentMonth + 1
    var dstYear        by remember { mutableIntStateOf(nextMonthYear) }
    var dstMonth       by remember { mutableIntStateOf(nextMonth) }
    var overwrite      by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = { Text("复制排班到其他月") },
        text   = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("将 ${currentYear}年${currentMonth}月 的排班复制到：",
                    style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("目标年份：", style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { dstYear-- }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Remove, "减年")
                    }
                    Text("${dstYear}年", fontWeight = FontWeight.Bold)
                    IconButton(onClick = { dstYear++ }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Add, "加年")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..12).forEach { m ->
                        val selected = m == dstMonth
                        Surface(
                            shape    = RoundedCornerShape(8.dp),
                            color    = if (selected) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.weight(1f).clickable { dstMonth = m }
                        ) {
                            Text("$m", textAlign = TextAlign.Center,
                                style  = MaterialTheme.typography.bodySmall,
                                color     = if (selected) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier  = Modifier.padding(vertical = 6.dp))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = overwrite, onCheckedChange = { overwrite = it })
                    Text("覆盖已有排班", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dstYear, dstMonth, overwrite) }) {
                Text("确认复制")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ════════════════════════════════════════════════════════════════════════════
// 复制排班工具栏
// ════════════════════════════════════════════════════════════════════════════

@Composable
internal fun CopyRangeToolbar(
    phase: Int,
    sourceCount: Int,
    sourceStart: String?,
    sourceEnd: String?,
    targetDate: String?,
    onConfirmPhase1: () -> Unit,
    onClearSelection: () -> Unit,
    onBackToPhase1: () -> Unit,
    onConfirmExecute: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shadowElevation = 4.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // 动态提示语
            Text(
                text = when {
                    phase == 1 && sourceCount == 0 -> "请选择要复制的源日期范围"
                    phase == 1 && sourceEnd == null -> "已选起始日期：$sourceStart，请点击结束日期"
                    phase == 1 -> "已选范围：$sourceStart ~ $sourceEnd（${sourceCount}天）"
                    phase == 2 && targetDate == null -> "请点击目标起始位置"
                    else -> "目标起始位置：$targetDate"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            // 按钮行
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (phase == 1) {
                    OutlinedButton(
                        onClick = onConfirmPhase1,
                        enabled = sourceEnd != null,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("确认复制", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(
                        onClick = onClearSelection,
                        enabled = sourceCount > 0,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("取消选择", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("退出", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    OutlinedButton(
                        onClick = onConfirmExecute,
                        enabled = targetDate != null,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("确认应用", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(
                        onClick = onBackToPhase1,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("返回上一级", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("退出", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 年月选择器弹窗（纵向滚动列表 + 公历/农历切换）
// ════════════════════════════════════════════════════════════════════════════

@Composable
internal fun YearMonthPickerDialog(
    currentYear:  Int,
    currentMonth: Int,
    currentDay:   Int,
    onConfirm:    (year: Int, month: Int, day: Int) -> Unit,
    onDismiss:    () -> Unit
) {
    var year  by remember { mutableIntStateOf(currentYear) }
    var month by remember { mutableIntStateOf(currentMonth) }
    var day   by remember { mutableIntStateOf(currentDay) }
    var isLunarMode by remember { mutableStateOf(false) }

    // 农历月份名称
    val lunarMonthNames = listOf("正月","二月","三月","四月","五月","六月","七月","八月","九月","十月","冬月","腊月")

    // 年份范围：当前年份前后30年
    val yearRange  = (currentYear - 30..currentYear + 30).toList()
    val monthRange = (1..12).toList()

    // 计算所选年月的最大天数（处理闰年等）
    val maxDay = remember(year, month) {
        YearMonth.of(year, month).lengthOfMonth()
    }
    val dayRange = (1..maxDay).toList()

    // 当月份/年份变化导致天数缩小时，自动收窄 day
    LaunchedEffect(maxDay) {
        if (day > maxDay) day = maxDay
    }

    // 各列滚动状态
    val yearListState  = rememberLazyListState()
    val monthListState = rememberLazyListState()
    val dayListState   = rememberLazyListState()

    // 打开弹窗时自动滚动到当前选中项
    LaunchedEffect(Unit) {
        val yIdx = yearRange.indexOf(year)
        if (yIdx >= 0) yearListState.scrollToItem(yIdx)
        val mIdx = monthRange.indexOf(month)
        if (mIdx >= 0) monthListState.scrollToItem(mIdx)
        val dIdx = dayRange.indexOf(day)
        if (dIdx >= 0) dayListState.scrollToItem(dIdx)
    }

    // 月份/年份切换后，day 列自动滚动到选中日
    LaunchedEffect(day, maxDay) {
        val dIdx = dayRange.indexOf(day)
        if (dIdx >= 0) dayListState.animateScrollToItem(dIdx)
    }

    // ── 公历/农历切换项 ──────────────────────────────────────────────
    val calendarTypes = listOf("公历", "农历")

    // ── 辅助函数：获取年份显示文本 ───────────────────────────────────
    fun yearDisplayText(y: Int): String {
        if (!isLunarMode) return "${y}年"
        val lunar = LunarCalendar.solarToLunar(y, 7, 1) // 取年中作为该公历年对应的农历年
        return lunar.yearGanZhi // 例如 "甲辰年"
    }

    // ── 辅助函数：获取月份显示文本 ───────────────────────────────────
    fun monthDisplayText(m: Int): String {
        if (!isLunarMode) return "${m}月"
        return lunarMonthNames[m - 1] // 例如 "四月"
    }

    // ── 辅助函数：获取日期显示文本 ───────────────────────────────────
    fun dayDisplayText(d: Int): String {
        if (!isLunarMode) return "${d}日"
        val lunar = LunarCalendar.solarToLunar(year, month, d)
        return lunar.dayText // 例如 "初八"
    }

    // ── 预览文本 ────────────────────────────────────────────────────
    val previewText = if (isLunarMode) {
        val lunarYear  = LunarCalendar.solarToLunar(year, month, day).yearGanZhi
        val lunarMonth = lunarMonthNames[month - 1]
        val lunarDay   = LunarCalendar.solarToLunar(year, month, day).dayText
        "$lunarYear $lunarMonth$lunarDay"
    } else {
        "${year}年${month}月${day}日"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("选择日期")
                // 公历/农历切换
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    calendarTypes.forEachIndexed { idx, label ->
                        val isActive = (idx == 0 && !isLunarMode) || (idx == 1 && isLunarMode)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isActive) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { isLunarMode = idx == 1 }
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        },
        text   = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 当前选择的日期预览
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    textAlign = TextAlign.Center
                )

                // ── 四列横向排列 ──────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 第一列：公历 / 农历
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(calendarTypes) { type ->
                            val isSelected = (type == "公历" && !isLunarMode) || (type == "农历" && isLunarMode)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                       else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isLunarMode = (type == "农历") }
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = type,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                           else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    // 第二列：年份
                    LazyColumn(
                        state = yearListState,
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(yearRange) { y ->
                            val isSelected = y == year
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                       else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { year = y }
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = yearDisplayText(y),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                           else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    // 第三列：月份
                    LazyColumn(
                        state = monthListState,
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(monthRange) { m ->
                            val isSelected = m == month
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                       else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { month = m }
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = monthDisplayText(m),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                           else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    // 第四列：日期
                    LazyColumn(
                        state = dayListState,
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(dayRange) { d ->
                            val isSelected = d == day
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                       else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { day = d }
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = dayDisplayText(d),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                           else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(year, month, day) }) {
                Text("跳转到选中日期")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
