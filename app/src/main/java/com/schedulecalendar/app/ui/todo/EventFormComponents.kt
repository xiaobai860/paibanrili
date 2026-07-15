// app/src/main/java/com/schedulecalendar/app/ui/todo/EventFormComponents.kt
package com.schedulecalendar.app.ui.todo

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schedulecalendar.app.data.calendar.CalendarAccountInfo
import com.schedulecalendar.app.ui.component.ImeAdaptiveOutlinedTextField
import java.util.Calendar
import java.util.Locale

// ── 重复规则选项 ────────────────────────────────────────────────────────

enum class RepeatRule(val label: String, val rrule: String?) {
    NONE("不重复", null),
    DAILY("每天", "FREQ=DAILY"),
    WEEKLY("每周", "FREQ=WEEKLY"),
    BIWEEKLY("每两周", "FREQ=WEEKLY;INTERVAL=2"),
    MONTHLY("每月", "FREQ=MONTHLY"),
    YEARLY("每年", "FREQ=YEARLY")
}

// ── 提醒时间选项 ────────────────────────────────────────────────────────

enum class ReminderTime(val label: String, val minutes: Int) {
    NONE("不提醒", -1),
    AT_TIME("事件发生时", 0),
    FIVE_MIN("5 分钟前", 5),
    FIFTEEN_MIN("15 分钟前", 15),
    THIRTY_MIN("30 分钟前", 30),
    ONE_HOUR("1 小时前", 60),
    TWO_HOUR("2 小时前", 120),
    ONE_DAY("1 天前", 1440)
}

// ── 预设事件颜色 ────────────────────────────────────────────────────────

val EventPresetColors = listOf(
    "#DC2626", "#EA580C", "#D97706", "#059669", "#0891B2",
    "#2563EB", "#7C3AED", "#DB2777", "#4B5563", "#6B7280"
)

// ── 表单数据类 ────────────────────────────────────────────────────────

data class EventFormData(
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val isAllDay: Boolean = false,
    val selectedYear: Int = Calendar.getInstance().get(Calendar.YEAR),
    val selectedMonth: Int = Calendar.getInstance().get(Calendar.MONTH) + 1,
    val selectedDay: Int = Calendar.getInstance().get(Calendar.DAY_OF_MONTH),
    val startHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) + 1,
    val startMinute: Int = 0,
    val endHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) + 2,
    val endMinute: Int = 0,
    val repeatRule: RepeatRule = RepeatRule.NONE,
    val reminderTime: ReminderTime = ReminderTime.FIFTEEN_MIN,
    val selectedColor: String = EventPresetColors.first(),
    val selectedAccountId: Long? = null
)

// ════════════════════════════════════════════════════════════════════════════
// 共享表单组件
// ════════════════════════════════════════════════════════════════════════════

// ── 标题输入 ────────────────────────────────────────────────────────

@Composable
fun EventTitleField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null
) {
    ImeAdaptiveOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("添加标题") },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Title, null) },
        textStyle = MaterialTheme.typography.titleMedium,
        scrollState = scrollState
    )
}

// ── 全天事件开关 ────────────────────────────────────────────────────

@Composable
fun EventAllDaySwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("全天事件", style = MaterialTheme.typography.bodyLarge)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ── 日期选择卡片 ────────────────────────────────────────────────────

@Composable
fun EventDateCard(
    year: Int, month: Int, day: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.CalendarToday, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("日期", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${year}年${month}月${day}日",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ── 时间选择卡片（开始/结束） ───────────────────────────────────────

@Composable
fun EventTimeCards(
    startHour: Int, startMinute: Int,
    endHour: Int, endMinute: Int,
    onStartClick: () -> Unit,
    onEndClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedCard(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            onClick = onStartClick
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text("开始时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    String.format(Locale.getDefault(), "%02d:%02d", startHour, startMinute),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        OutlinedCard(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            onClick = onEndClick
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text("结束时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    String.format(Locale.getDefault(), "%02d:%02d", endHour, endMinute),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ── 描述输入 ────────────────────────────────────────────────────────

@Composable
fun EventDescriptionField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null
) {
    ImeAdaptiveOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("描述（可选）") },
        modifier = modifier,
        minLines = 2,
        maxLines = Int.MAX_VALUE,
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, null) },
        scrollState = scrollState
    )
}

// ── 地点输入 ────────────────────────────────────────────────────────

@Composable
fun EventLocationField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null
) {
    ImeAdaptiveOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("地点（可选）") },
        modifier = modifier,
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.LocationOn, null) },
        scrollState = scrollState
    )
}

// ── 重复规则选择器 ──────────────────────────────────────────────────

@Composable
fun EventRepeatSelector(
    selected: RepeatRule,
    onSelected: (RepeatRule) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        onClick = { expanded = true }
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Repeat, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("重复", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text("重复规则") },
            text = {
                Column {
                    RepeatRule.entries.forEach { rule ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelected(rule)
                                    expanded = false
                                }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected == rule,
                                onClick = {
                                    onSelected(rule)
                                    expanded = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(rule.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }) { Text("取消") }
            }
        )
    }
}

// ── 提醒时间选择器 ──────────────────────────────────────────────────

@Composable
fun EventReminderSelector(
    selected: ReminderTime,
    onSelected: (ReminderTime) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        onClick = { expanded = true }
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("提醒", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text("提醒时间") },
            text = {
                Column {
                    ReminderTime.entries.forEach { time ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelected(time)
                                    expanded = false
                                }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected == time,
                                onClick = {
                                    onSelected(time)
                                    expanded = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(time.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }) { Text("取消") }
            }
        )
    }
}

// ── 颜色选择器 ──────────────────────────────────────────────────────

@Composable
fun EventColorSelector(
    selectedColor: String,
    onColorSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("颜色", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EventPresetColors.forEach { hex ->
                    val color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrElse { Color.Gray }
                    val isSelected = hex == selectedColor
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier
                            )
                            .clickable { onColorSelected(hex) }
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check, null,
                                tint = Color.White,
                                modifier = Modifier.align(Alignment.Center).size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 日历账户选择器 ──────────────────────────────────────────────────

@Composable
fun EventAccountSelector(
    accounts: List<CalendarAccountInfo>,
    selectedId: Long?,
    onSelected: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (accounts.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val selectedAccount = accounts.find { it.id == selectedId } ?: accounts.firstOrNull()
    // 显示名后备：displayName -> accountName -> "选择日历"
    val selectedDisplayName = selectedAccount?.let {
        it.displayName.ifBlank { it.accountName.ifBlank { "选择日历" } }
    } ?: "选择日历"

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        onClick = { expanded = true }
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("日历", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    selectedDisplayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (selectedAccount != null && selectedAccount.accountName.isNotEmpty() && selectedAccount.accountName != selectedAccount.displayName) {
                    Text(
                        selectedAccount.accountName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (expanded && accounts.size > 1) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text("选择日历") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    accounts.forEach { account ->
                        // 严格计算显示名，确保不为空
                        val acctDisplayName = account.displayName.trim().ifBlank {
                            account.accountName.trim().ifBlank { "未知日历" }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelected(account.id)
                                    expanded = false
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = account.id == selectedId || (selectedId == null && account == accounts.first()),
                                onClick = {
                                    onSelected(account.id)
                                    expanded = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(acctDisplayName, style = MaterialTheme.typography.bodyLarge)
                                // 仅当accountName与displayName不同且非空时显示副标题
                                val trimmedAccountName = account.accountName.trim()
                                if (trimmedAccountName.isNotEmpty() && trimmedAccountName != acctDisplayName) {
                                    Text(trimmedAccountName, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }) { Text("取消") }
            }
        )
    }
}

// ── 分组标题 ────────────────────────────────────────────────────────

@Composable
fun FormSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}
