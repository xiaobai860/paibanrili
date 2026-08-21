package com.schedulecalendar.app.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.commandiron.wheel_picker_compose.core.WheelPickerDefaults
import com.commandiron.wheel_picker_compose.core.WheelTextPicker
import java.time.YearMonth

/**
 * WheelPicker 年/月/日 三列滚轮日期选择弹窗
 * 紧凑布局，最大宽度为屏幕 85%
 *
 * @param title       弹窗标题
 * @param currentYear 当前选中年份
 * @param currentMonth 当前选中月份（1-12）
 * @param currentDay  当前选中日期（1-31）
 * @param yearList    年份列表
 * @param monthLabels 月份显示标签（12项），为 null 时使用默认 "X月"
 * @param dayLabels   日期显示标签，为 null 时使用默认 "X日"
 * @param onConfirm   确认回调 (year, month, day)
 * @param onDismiss   取消回调
 */
@Composable
fun WheelFullDatePickerDialog(
    title: String,
    currentYear: Int,
    currentMonth: Int,
    currentDay: Int,
    yearList: List<Int>,
    monthLabels: List<String>? = null,
    dayLabels: List<String>? = null,
    /** 非null时覆盖自动计算的最大天数（用于农历等场景） */
    fixedMaxDay: Int? = null,
    onConfirm: (year: Int, month: Int, day: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var snappedYear by remember { mutableIntStateOf(currentYear) }
    var snappedMonth by remember { mutableIntStateOf(currentMonth) }
    var snappedDay by remember { mutableIntStateOf(currentDay) }

    // 月份标签
    val mLabels = remember(monthLabels) {
        monthLabels ?: (1..12).map { "${it}月" }
    }
    // 根据当前选中年月计算最大天数（或使用固定值）
    val maxDay = remember(snappedYear, snappedMonth, fixedMaxDay) {
        fixedMaxDay ?: try { YearMonth.of(snappedYear, snappedMonth).lengthOfMonth() } catch (_: Exception) { 30 }
    }
    val dayList = (1..maxDay).toList()
    // 日期标签
    val dLabels = remember(dayLabels, maxDay) {
        dayLabels?.take(maxDay) ?: dayList.map { "${it}日" }
    }

    // 当月份变化导致天数缩小时，自动收窄 day
    LaunchedEffect(maxDay) {
        if (snappedDay > maxDay) snappedDay = maxDay
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.85f).wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // 三列滚轮：年 / 月 / 日
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 年
                    WheelTextPicker(
                        size = DpSize(110.dp, 128.dp),
                        texts = yearList.map { "${it}年" },
                        rowCount = 3,
                        startIndex = yearList.indexOf(currentYear).coerceAtLeast(0),
                        style = MaterialTheme.typography.titleMedium,
                        selectorProperties = WheelPickerDefaults.selectorProperties(
                            enabled = true,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            border = null
                        ),
                        onScrollFinished = { snappedIndex ->
                            snappedYear = yearList[snappedIndex]
                            null
                        }
                    )

                    // 月
                    WheelTextPicker(
                        size = DpSize(80.dp, 128.dp),
                        texts = mLabels,
                        rowCount = 3,
                        startIndex = (currentMonth - 1).coerceIn(0, mLabels.size - 1),
                        style = MaterialTheme.typography.titleMedium,
                        selectorProperties = WheelPickerDefaults.selectorProperties(
                            enabled = true,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            border = null
                        ),
                        onScrollFinished = { snappedIndex ->
                            snappedMonth = snappedIndex + 1
                            null
                        }
                    )

                    // 日
                    WheelTextPicker(
                        size = DpSize(80.dp, 128.dp),
                        texts = dLabels,
                        rowCount = 3,
                        startIndex = (currentDay - 1).coerceIn(0, dLabels.size - 1),
                        style = MaterialTheme.typography.titleMedium,
                        selectorProperties = WheelPickerDefaults.selectorProperties(
                            enabled = true,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            border = null
                        ),
                        onScrollFinished = { snappedIndex ->
                            snappedDay = snappedIndex + 1
                            null
                        }
                    )
                }

                Spacer(Modifier.height(16.dp))

                // 确认 / 取消按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            onConfirm(
                                snappedYear.coerceIn(yearList.first(), yearList.last()),
                                snappedMonth.coerceIn(1, 12),
                                snappedDay.coerceIn(1, maxDay)
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("确定", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * WheelPicker year-month picker (using WheelPickerCompose)
 */
@Composable
fun WheelDatePickerDialog(
    currentYear: Int,
    currentMonth: Int,
    onConfirm: (year: Int, month: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val currentCalYear = java.time.Year.now().value
    val yearRange = (currentCalYear - 30)..(currentCalYear + 30)
    val yearList = yearRange.toList()
    val monthList = (1..12).toList()

    var snappedYear by remember { mutableIntStateOf(currentYear) }
    var snappedMonth by remember { mutableIntStateOf(currentMonth) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.85f).wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "选择年月",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Year + Month wheel pickers side by side
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Year picker
                    WheelTextPicker(
                        size = DpSize(130.dp, 128.dp),
                        texts = yearList.map { "${it}年" },
                        rowCount = 3,
                        startIndex = yearList.indexOf(currentYear).coerceAtLeast(0),
                        style = MaterialTheme.typography.titleMedium,
                        selectorProperties = WheelPickerDefaults.selectorProperties(
                            enabled = true,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            border = null
                        ),
                        onScrollFinished = { snappedIndex ->
                            snappedYear = yearList[snappedIndex]
                            null
                        }
                    )

                    // Month picker
                    WheelTextPicker(
                        size = DpSize(100.dp, 128.dp),
                        texts = monthList.map { "${it}月" },
                        rowCount = 3,
                        startIndex = monthList.indexOf(currentMonth).coerceAtLeast(0),
                        style = MaterialTheme.typography.titleMedium,
                        selectorProperties = WheelPickerDefaults.selectorProperties(
                            enabled = true,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            border = null
                        ),
                        onScrollFinished = { snappedIndex ->
                            snappedMonth = monthList[snappedIndex]
                            null
                        }
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Confirm / Cancel buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            onConfirm(
                                snappedYear.coerceIn(yearRange.first, yearRange.last),
                                snappedMonth.coerceIn(1, 12)
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("确认", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
