// app/src/main/java/com/schedulecalendar/app/ui/component/CommonComponents.kt
package com.schedulecalendar.app.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schedulecalendar.app.ui.theme.ShiftPresetColors
import kotlinx.coroutines.delay

/** 顶部 AppBar with back button */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    // title 为空且无导航图标和操作按钮时，不渲染任何内容以节省空间
    if (title.isEmpty() && onBack == null && actions == {}) return

    TopAppBar(
        windowInsets = WindowInsets(0, 0, 0, 0),
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor    = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

/** 班次颜色圆点 */
@Composable
fun ShiftColorDot(hexColor: String, size: Int = 10) {
    val color = runCatching { Color(hexColor.toColorInt()) }.getOrElse { Color(0xFF059669) }
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** 班次颜色选择器（2行×6列，共12色；每行 6 个单元格均分水平空间，圆形自适应不挤压变形） */
@Composable
fun ColorPicker(selected: String, onSelect: (String) -> Unit) {
    val rows = ShiftPresetColors.chunked(6)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { rowColors ->
            // 固定尺寸圆点 + 水平均分（SpaceEvenly 自带间隙），不再随宽度撑大
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                rowColors.forEach { hex ->
                    val color = runCatching { Color(hex.toColorInt()) }.getOrElse { Color.Gray }
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(if (hex == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clickable { onSelect(hex) }
                    )
                }
            }
        }
    }
}

/** 信息统计卡片 */
@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, containerColor: Color = MaterialTheme.colorScheme.primaryContainer) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = containerColor), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 月份切换导航 */
@Composable
fun MonthNavigator(year: Int, month: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上月") }
        Text("${year}年${month}月", style = MaterialTheme.typography.titleLarge)
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下月") }
    }
}

/** 设置项 Row */
@Composable
fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        content()
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
}

/**
 * 禁用浮动标签动画的 OutlinedTextField 颜色配置。
 * 将 focusedLabelColor 与 unfocusedLabelColor 设为相同值，
 * label 始终常驻显示在边框固定位置，不随焦点状态产生视觉差异。
 */
@Composable
fun stableLabelColors() = OutlinedTextFieldDefaults.colors(
    focusedLabelColor   = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
)

/** 可编辑数字设置行 */
@Composable
fun NumericSettingRow(label: String, value: String, onValueChange: (String) -> Unit) {
    SettingRow(label) {
        OutlinedTextField(
            value         = value,
            onValueChange = onValueChange,
            modifier      = Modifier.width(120.dp),
            singleLine    = true,
            textStyle     = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * 时间选择器输入框 —— 点击后弹出 Material3 TimePicker 对话框。
 * 当提供 [onRequestDialog] 时，点击仅触发回调（由父级渲染对话框，适用于 LazyColumn 等场景）；
 * 否则使用内部对话框（自包含）。
 *
 * @param time        当前时间字符串，格式 "HH:mm"，空字符串表示未设置
 * @param onTimeChange 用户选择新时间后的回调
 * @param label       输入框标签文字
 * @param enabled     是否可交互
 * @param defaultTime 默认时间字符串，格式 "HH:mm"，当 time 为空时用作选择器初始值
 * @param onRequestDialog 可选回调，提供后点击仅触发此回调；未提供时使用内部对话框
 * @param modifier    外部修饰符
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerField(
    time: String,
    onTimeChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    defaultTime: String = "",
    onRequestDialog: (() -> Unit)? = null
) {
    var showDialog by remember { mutableStateOf(false) }

    val effectiveTime = if (time.isNotEmpty()) time else defaultTime
    val parts   = effectiveTime.split(":")
    val initH   = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
    val initM   = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0

    Box(modifier = modifier.clickable(enabled = enabled) {
        if (onRequestDialog != null) onRequestDialog() else showDialog = true
    }) {
        OutlinedTextField(
            value         = time,
            onValueChange = {},
            label         = { Text(label) },
            placeholder   = { Text("HH:mm") },
            readOnly      = true,
            enabled       = false,
            trailingIcon  = {
                Icon(Icons.Default.AccessTime, contentDescription = "选择时间",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            colors        = OutlinedTextFieldDefaults.colors(
                disabledTextColor         = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor       = MaterialTheme.colorScheme.outline,
                disabledLabelColor        = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledPlaceholderColor  = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedLabelColor         = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedLabelColor       = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }

    // 仅当未提供 onRequestDialog 时使用内部对话框
    if (onRequestDialog == null && showDialog) {
        val state = rememberTimePickerState(
            initialHour   = initH,
            initialMinute = initM,
            is24Hour      = true
        )
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label, style = MaterialTheme.typography.titleMedium) },
            text  = {
                Column(
                    modifier            = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TimePicker(state = state)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val hh = state.hour.toString().padStart(2, '0')
                    val mm = state.minute.toString().padStart(2, '0')
                    onTimeChange("$hh:$mm")
                    showDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 可展开时间选择器 —— 点击后弹出 Material3 TimePicker 对话框。
 * 收起状态下展示为 Trailing Icon 样式的按钮，样式遵循 CalendarScreen 中的设计范式。
 *
 * @param label          标签文本
 * @param time           当前时间字符串，格式 "HH:mm"
 * @param onTimeSelected 时间选定后的回调
 * @param modifier       外部修饰符
 * @param enabled        是否可交互
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandableTimePicker(
    label: String,
    time: String,
    onTimeSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var showDialog by remember { mutableStateOf(false) }

    val parts = time.split(":")
    val initH = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
    val initM = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0

    Box(modifier = modifier.clickable(enabled = enabled) { showDialog = true }) {
        OutlinedTextField(
            value         = time,
            onValueChange = {},
            label         = { Text(label) },
            placeholder   = { Text("HH:mm") },
            readOnly      = true,
            enabled       = false,
            trailingIcon  = {
                Icon(Icons.Default.AccessTime, contentDescription = "选择时间",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            colors        = OutlinedTextFieldDefaults.colors(
                disabledTextColor         = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor       = MaterialTheme.colorScheme.outline,
                disabledLabelColor        = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledPlaceholderColor  = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedLabelColor         = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedLabelColor       = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }

    if (showDialog) {
        val state = rememberTimePickerState(
            initialHour   = initH,
            initialMinute = initM,
            is24Hour      = true
        )
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label, style = MaterialTheme.typography.titleMedium) },
            text  = {
                Column(
                    modifier            = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TimePicker(state = state)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val hh = state.hour.toString().padStart(2, '0')
                    val mm = state.minute.toString().padStart(2, '0')
                    onTimeSelected("$hh:$mm")
                    showDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("取消") }
            }
        )
    }
}

/**
 * IME 自适应输入框 —— 自动处理软键盘遮挡问题。
 *
 * 核心机制：
 * 1. 获得焦点时等待 IME 弹出，然后精确滚动父容器使输入框下边缘刚好在键盘上方
 * 2. 内容增长导致高度变化时再次触发滚动
 * 3. 对于 Column+verticalScroll：传入 scrollState，自动精确计算滚动位置
 * 4. 对于 LazyColumn：通过 onFocused 回调由调用方处理滚动
 *
 * @param scrollState  父级 Column 的 ScrollState，传入后自动处理滚动
 * @param onFocused    LazyColumn 场景下的回调，获得焦点并等待 IME 后触发
 */
@Composable
fun ImeAdaptiveOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    textStyle: TextStyle? = null,
    scrollState: ScrollState? = null,
    onFocused: (suspend () -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val fieldHeight = remember { mutableIntStateOf(0) }
    val fieldYInRoot = remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val view = LocalView.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = textStyle ?: LocalTextStyle.current,
        modifier = modifier
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
            }
            .onSizeChanged { size ->
                fieldHeight.intValue = size.height
            }
            .onGloballyPositioned { coordinates: LayoutCoordinates ->
                fieldYInRoot.floatValue = coordinates.positionInRoot().y
            }
    )

    // 精确滚动：使输入框下边缘刚好在 IME 键盘上方
    suspend fun scrollAboveIme() {
        if (scrollState != null) {
            val fieldY = fieldYInRoot.floatValue
            val fieldBottom = fieldY + fieldHeight.intValue

            val windowRect = android.graphics.Rect()
            view.getWindowVisibleDisplayFrame(windowRect)

            val marginPx = with(density) { 16.dp.toPx() }
            val imeTopWithMargin = windowRect.bottom.toFloat() - marginPx

            val overflow = fieldBottom - imeTopWithMargin
            if (overflow > 0) {
                val target = (scrollState.value + overflow.toInt())
                    .coerceIn(0, scrollState.maxValue)
                scrollState.scrollTo(target)
            }
        } else {
            onFocused?.invoke()
        }
    }

    // 触发器 1：获得焦点时 → 等待 IME 完全弹出后滚动
    LaunchedEffect(isFocused) {
        if (isFocused) {
            delay(400)
            scrollAboveIme()
        }
    }

    // 触发器 2：内容增长导致高度变化时 → 快速重新滚动保持可见
    LaunchedEffect(fieldHeight.intValue) {
        if (isFocused && fieldHeight.intValue > 0) {
            delay(100)
            scrollAboveIme()
        }
    }
}
