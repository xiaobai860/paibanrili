// app/src/main/java/com/schedulecalendar/app/ui/detail/DisplaySchemesScreen.kt
package com.schedulecalendar.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.domain.model.DataRowConfig
import com.schedulecalendar.app.domain.model.DisplayItemType
import com.schedulecalendar.app.domain.model.DisplayScheme
import com.schedulecalendar.app.domain.model.isSpecialType
import com.schedulecalendar.app.domain.model.BUILTIN_SCHEME_ID
import com.schedulecalendar.app.domain.model.NO_SCHEME_ID
import com.schedulecalendar.app.domain.model.textColorForBackground
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import com.schedulecalendar.app.ui.component.stableLabelColors
import java.util.UUID

@Composable
fun DisplaySchemesScreen(navController: NavController, vm: DisplaySchemesViewModel = hiltViewModel()) {
    val schemes  by vm.schemes.collectAsStateWithLifecycle()
    var showEditor by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<DisplayScheme?>(null) }

    // 确保列表中始终包含"预设方案"选项，过滤掉内置预设方案
    val schemesWithNoScheme = remember(schemes) {
        val userSchemes = schemes.filter { it.id != BUILTIN_SCHEME_ID }
        val noScheme = userSchemes.find { it.id == NO_SCHEME_ID }
        if (noScheme != null) userSchemes else {
            // 无持久化方案时，默认激活预设方案
            val shouldActivate = userSchemes.isEmpty()
            listOf(
                DisplayScheme(
                    id = NO_SCHEME_ID,
                    name = "预设方案",
                    isNoScheme = true,
                    builtIn = true,
                    isActive = shouldActivate
                )
            ) + userSchemes
        }
    }

    Scaffold(
        topBar = { ScheduleTopBar("显示方案", onBack = { navController.popBackStack() }) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { editTarget = null; showEditor = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) { Icon(Icons.Filled.Add, "新增方案") }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("点击方案行可切换为当前激活方案", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(schemesWithNoScheme, key = { it.id }) { scheme ->
                SchemeCard(
                    scheme = scheme,
                    onActivate  = { vm.setActive(scheme.id) },
                    onEdit      = { if (!scheme.isNoScheme) { editTarget = scheme; showEditor = true } },
                    onDelete    = {
                        if (!scheme.isNoScheme) {
                            val updated = schemes.filter { it.id != scheme.id }
                            vm.save(updated.ifEmpty { emptyList() })
                        }
                    }
                )
            }
        }
    }

    if (showEditor) {
        SchemeEditorDialog(
            scheme    = editTarget,
            schemes   = schemes,
            onSave    = { s ->
                val updated = if (editTarget == null) schemes + s
                             else schemes.map { if (it.id == s.id) s else it }
                vm.save(updated)
                showEditor = false
            },
            onDismiss = { showEditor = false }
        )
    }
}

@Composable
private fun SchemeCard(scheme: DisplayScheme, onActivate: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick  = onActivate,
        colors   = CardDefaults.cardColors(
            containerColor = if (scheme.isActive) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(scheme.name, fontWeight = FontWeight.SemiBold)
                    if (scheme.isActive) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Check, contentDescription = "当前方案",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 固定项
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SchemeTag("农历",    true)
                    SchemeTag("节假日",  true)
                }
                // 用户自选项（四行数据）
                if (!scheme.isNoScheme && scheme.dataRows.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        scheme.dataRows.forEachIndexed { index, rowConfig ->
                            if (rowConfig.items.any { it != null }) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SchemeTag("第${index + 1}行", false)
                                    rowConfig.items.filterNotNull().forEach { item ->
                                        SchemeTag(item.label, true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!scheme.isActive && !scheme.isNoScheme) {
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, "编辑", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun SchemeTag(label: String, enabled: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun SchemeEditorDialog(scheme: DisplayScheme?, schemes: List<DisplayScheme>, onSave: (DisplayScheme) -> Unit, onDismiss: () -> Unit) {
    // 方案名称自动去重：新增时检测同名方案并自增后缀
    val defaultName = remember(scheme, schemes) {
        if (scheme != null) scheme.name
        else {
            val existingNames = schemes.map { it.name }.toSet()
            var candidate = "新方案"
            var idx = 0
            while (candidate in existingNames) {
                idx++
                candidate = "新方案$idx"
            }
            candidate
        }
    }
    var name by remember { mutableStateOf(defaultName) }
    // 四行数据行配置
    var dataRows by remember {
        mutableStateOf(
            scheme?.dataRows?.take(4) ?: listOf(
                DataRowConfig(items = listOf(DisplayItemType.SHIFT, DisplayItemType.STATUS)),
                DataRowConfig(),
                DataRowConfig(),
                DataRowConfig()
            )
        )
    }

    // 当前预览配置
    val previewScheme = remember(name, dataRows) {
        DisplayScheme(
            name = name.ifBlank { "方案" },
            dataRows = dataRows
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (scheme == null) "新增方案" else "编辑方案") },
        text  = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 方案名称
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("方案名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = stableLabelColors()
                )

                // 预览区域 + 颜色选择器（横向排列）
                Text(
                    "预览与颜色配置",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 左侧：预览区域（宽度56dp，模拟 DayCell）
                    MiniDayCellPreview(
                        dataRows = dataRows,
                        modifier = Modifier.width(56.dp)
                    )
                    // 右侧：颜色选择器网格（2列4行）
                    ColorPickerGrid(
                        dataRows = dataRows,
                        onLeftColorChange = { rowIndex, color ->
                            dataRows = dataRows.toMutableList().apply {
                                set(rowIndex, get(rowIndex).copy(backgroundColorLeft = color))
                            }
                        },
                        onRightColorChange = { rowIndex, color ->
                            dataRows = dataRows.toMutableList().apply {
                                set(rowIndex, get(rowIndex).copy(backgroundColorRight = color))
                            }
                        },
                        previewHeight = 88.dp  // 20 + 2 + 12 + 3 + 4*(12 + 1) = 87dp
                    )
                }

                // 四行数据行配置
                Text(
                    "数据行配置（每行最多2项）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                dataRows.forEachIndexed { rowIndex, rowConfig ->
                    DataRowEditor(
                        rowIndex = rowIndex,
                        rowConfig = rowConfig,
                        onConfigChange = { newConfig ->
                            dataRows = dataRows.toMutableList().apply {
                                set(rowIndex, newConfig)
                            }
                        },
                        allDataRows = dataRows,
                        currentRowIndex = rowIndex
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(DisplayScheme(
                    id       = scheme?.id ?: UUID.randomUUID().toString(),
                    name     = name.ifBlank { "方案" },
                    dataRows = dataRows,
                    isActive = scheme?.isActive ?: false
                ))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataRowEditor(
    rowIndex: Int,
    rowConfig: DataRowConfig,
    onConfigChange: (DataRowConfig) -> Unit,
    allDataRows: List<DataRowConfig>,
    currentRowIndex: Int
) {
    var showLeftColorPicker by remember { mutableStateOf(false) }
    var showRightColorPicker by remember { mutableStateOf(false) }
    var leftExpanded by remember { mutableStateOf(false) }
    var rightExpanded by remember { mutableStateOf(false) }

    // 收集所有行已使用的数据项（排除当前行）
    val usedItems = remember(allDataRows, currentRowIndex) {
        allDataRows.filterIndexed { index, _ -> index != currentRowIndex }
            .flatMap { it.items }
            .filterNotNull()
            .toSet()
    }

    // 当前行已选的数据项
    val currentLeftItem = rowConfig.items.getOrNull(0)
    val currentRightItem = rowConfig.items.getOrNull(1)

    // 左侧选项：排除其他行使用的 + 当前行右侧已选的
    val leftOptions = DisplayItemType.entries.filter { type ->
        type !in usedItems && type != currentRightItem
    }
    // 右侧选项：排除其他行使用的 + 当前行左侧已选的
    val rightOptions = DisplayItemType.entries.filter { type ->
        type !in usedItems && type != currentLeftItem
    }

    // 判断左侧/右侧选中的类型是否为特殊类型（SHIFT/STATUS）
    val isLeftSpecial = currentLeftItem?.isSpecialType == true
    val isRightSpecial = currentRightItem?.isSpecialType == true

    // 特殊类型的默认背景色
    val leftSpecialColor = currentLeftItem?.defaultColor
    val rightSpecialColor = currentRightItem?.defaultColor

    // 当左侧选了特殊类型时，自动设置背景色并禁用颜色选择
    val effectiveLeftColor = if (isLeftSpecial) leftSpecialColor else rowConfig.backgroundColorLeft
    val effectiveRightColor = if (isRightSpecial) rightSpecialColor else rowConfig.backgroundColorRight

    // 当选择/取消特殊类型时，自动同步背景色
    LaunchedEffect(currentLeftItem, isLeftSpecial, leftSpecialColor) {
        if (isLeftSpecial && rowConfig.backgroundColorLeft != leftSpecialColor) {
            onConfigChange(rowConfig.copy(backgroundColorLeft = leftSpecialColor))
        } else if (!isLeftSpecial && leftSpecialColor != null && rowConfig.backgroundColorLeft == leftSpecialColor) {
            // 切换回非特殊类型，清除特殊颜色
            onConfigChange(rowConfig.copy(backgroundColorLeft = null))
        }
    }
    LaunchedEffect(currentRightItem, isRightSpecial, rightSpecialColor) {
        if (isRightSpecial && rowConfig.backgroundColorRight != rightSpecialColor) {
            onConfigChange(rowConfig.copy(backgroundColorRight = rightSpecialColor))
        } else if (!isRightSpecial && rightSpecialColor != null && rowConfig.backgroundColorRight == rightSpecialColor) {
            onConfigChange(rowConfig.copy(backgroundColorRight = null))
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            // 行标题
            Text(
                "第${rowIndex + 1}行",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 2.dp)
            )

            // 双下拉输入框布局（固定高度容器防止行高变化）
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ── 左侧下拉框 ──
                ExposedDropdownMenuBox(
                    expanded = leftExpanded,
                    onExpandedChange = { leftExpanded = !leftExpanded },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = currentLeftItem?.label ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("数据项1", fontSize = 11.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = leftExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        ),
                        singleLine = true,
                        colors = stableLabelColors()
                    )
                    ExposedDropdownMenu(
                        expanded = leftExpanded,
                        onDismissRequest = { leftExpanded = false }
                    ) {
                        // “无”选项（左侧）
                        DropdownMenuItem(
                            text = { Text("无", fontSize = 12.sp, lineHeight = 16.sp) },
                            onClick = {
                                // 清空左侧数据项，右侧数据项保持不变
                                val newItems = rowConfig.items.toMutableList()
                                if (newItems.isNotEmpty()) newItems[0] = null
                                onConfigChange(rowConfig.copy(items = newItems))
                                leftExpanded = false
                            },
                            leadingIcon = if (currentLeftItem == null) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            } else null
                        )
                        leftOptions.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.label, fontSize = 12.sp, lineHeight = 16.sp) },
                                onClick = {
                                    val newItems = if (currentLeftItem == type) {
                                        // 取消选中
                                        rowConfig.items.map { if (it == type) null else it }
                                    } else {
                                        // 选中（替换左侧，保留右侧不变）
                                        val new = rowConfig.items.toMutableList()
                                        if (new.isEmpty()) new.add(type) else new[0] = type
                                        new
                                    }
                                    onConfigChange(rowConfig.copy(items = newItems))
                                    leftExpanded = false
                                },
                                leadingIcon = if (currentLeftItem == type) {
                                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                } else null
                            )
                        }
                    }
                }

                // ── 右侧下拉框 ──
                ExposedDropdownMenuBox(
                    expanded = rightExpanded,
                    onExpandedChange = { rightExpanded = !rightExpanded },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = currentRightItem?.label ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("数据项2", fontSize = 11.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = rightExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        ),
                        singleLine = true,
                        colors = stableLabelColors()
                    )
                    ExposedDropdownMenu(
                        expanded = rightExpanded,
                        onDismissRequest = { rightExpanded = false }
                    ) {
                        // “无”选项（右侧）
                        DropdownMenuItem(
                            text = { Text("无", fontSize = 12.sp, lineHeight = 16.sp) },
                            onClick = {
                                // 清空右侧数据项，左侧数据项保持不变
                                val newItems = rowConfig.items.toMutableList()
                                if (newItems.size > 1) newItems[1] = null
                                onConfigChange(rowConfig.copy(items = newItems))
                                rightExpanded = false
                            },
                            leadingIcon = if (currentRightItem == null) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            } else null
                        )
                        rightOptions.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.label, fontSize = 12.sp, lineHeight = 16.sp) },
                                onClick = {
                                    val newItems = if (currentRightItem == type) {
                                        // 取消选中右侧
                                        rowConfig.items.map { if (it == type) null else it }
                                    } else {
                                        // 选中右侧（保留左侧不变）
                                        val new = rowConfig.items.toMutableList()
                                        while (new.size < 2) new.add(null)
                                        new[1] = type
                                        new
                                    }
                                    onConfigChange(rowConfig.copy(items = newItems))
                                    rightExpanded = false
                                },
                                leadingIcon = if (currentRightItem == type) {
                                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                } else null
                            )
                        }
                    }
                }
            }

            // ── 颜色选择区域 ──
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 左侧颜色选择
                Column(modifier = Modifier.weight(1f)) {
                    Text("背景色1", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isLeftSpecial) {
                            // 特殊类型：空白+锁图标
                            Surface(
                                modifier = Modifier.size(20.dp),
                                shape = RoundedCornerShape(3.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Lock, contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("自动", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Surface(
                                onClick = { showLeftColorPicker = true },
                                modifier = Modifier.size(20.dp),
                                shape = RoundedCornerShape(3.dp),
                                color = rowConfig.backgroundColorLeft?.let {
                                    try {
                                        Color(android.graphics.Color.parseColor(it))
                                    } catch (_: Exception) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                } ?: MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {}
                            if (rowConfig.backgroundColorLeft != null) {
                                Spacer(modifier = Modifier.width(4.dp))
                                TextButton(
                                    onClick = { onConfigChange(rowConfig.copy(backgroundColorLeft = null)) },
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                                    modifier = Modifier.height(20.dp)
                                ) {
                                    Text("清除", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                }

                // 右侧颜色选择
                Column(modifier = Modifier.weight(1f)) {
                    Text("背景色2", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isRightSpecial) {
                            Surface(
                                modifier = Modifier.size(20.dp),
                                shape = RoundedCornerShape(3.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Lock, contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("自动", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Surface(
                                onClick = { showRightColorPicker = true },
                                modifier = Modifier.size(20.dp),
                                shape = RoundedCornerShape(3.dp),
                                color = rowConfig.backgroundColorRight?.let {
                                    try {
                                        Color(android.graphics.Color.parseColor(it))
                                    } catch (_: Exception) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                } ?: MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {}
                            if (rowConfig.backgroundColorRight != null) {
                                Spacer(modifier = Modifier.width(4.dp))
                                TextButton(
                                    onClick = { onConfigChange(rowConfig.copy(backgroundColorRight = null)) },
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                                    modifier = Modifier.height(20.dp)
                                ) {
                                    Text("清除", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 左侧颜色选择器对话框（仅非特殊类型时可用）
    if (showLeftColorPicker && !isLeftSpecial) {
        SimpleColorPicker(
            initialColor = rowConfig.backgroundColorLeft,
            onColorSelected = { color ->
                onConfigChange(rowConfig.copy(backgroundColorLeft = color))
                showLeftColorPicker = false
            },
            onClear = {
                onConfigChange(rowConfig.copy(backgroundColorLeft = null))
                showLeftColorPicker = false
            },
            onDismiss = { showLeftColorPicker = false }
        )
    }

    // 右侧颜色选择器对话框（仅非特殊类型时可用）
    if (showRightColorPicker && !isRightSpecial) {
        SimpleColorPicker(
            initialColor = rowConfig.backgroundColorRight,
            onColorSelected = { color ->
                onConfigChange(rowConfig.copy(backgroundColorRight = color))
                showRightColorPicker = false
            },
            onClear = {
                onConfigChange(rowConfig.copy(backgroundColorRight = null))
                showRightColorPicker = false
            },
            onDismiss = { showRightColorPicker = false }
        )
    }
}



// ── Mini DayCell 预览组件（宽度56dp，模拟实际日期格子）────────────────────────
@Composable
private fun MiniDayCellPreview(
    dataRows: List<DataRowConfig>,
    modifier: Modifier = Modifier
) {
    // DayCell 布局常量（与 CalendarScreen.kt 保持一致）
    val dateHeight = 20.dp
    val lunarGap = 2.dp
    val lunarHeight = 12.dp
    val dataGap = 3.dp
    val dataRowHeight = 12.dp
    val dataRowGap = 1.dp
    val lunarTextSize = MaterialTheme.typography.labelSmall.fontSize
    val rowTextSize = 10.sp

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 0.5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // 1. 日期数字（20dp）
            Box(
                modifier = Modifier.fillMaxWidth().height(dateHeight),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    "27",
                    fontSize = 18.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            // 2. 农历间距 2dp
            Spacer(Modifier.height(lunarGap))
            // 3. 农历文字（12dp）
            Box(
                modifier = Modifier.fillMaxWidth().height(lunarHeight),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "廿八",
                    fontSize = lunarTextSize,
                    lineHeight = lunarTextSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 4. 农历→数据行间距 3dp
            Spacer(Modifier.height(dataGap))
            // 5. 数据行区域（四行）
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(dataRowGap)
            ) {
                dataRows.forEach { rowConfig ->
                    if (rowConfig.items.any { it != null }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(dataRowHeight),
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            rowConfig.items.filterNotNull().forEachIndexed { index, item ->
                                val bgColor = if (index == 0) {
                                    rowConfig.backgroundColorLeft?.let {
                                        try {
                                            Color(android.graphics.Color.parseColor(it))
                                        } catch (_: Exception) {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    } ?: MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    rowConfig.backgroundColorRight?.let {
                                        try {
                                            Color(android.graphics.Color.parseColor(it))
                                        } catch (_: Exception) {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    } ?: MaterialTheme.colorScheme.surfaceVariant
                                }

                                Surface(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    shape = RoundedCornerShape(2.dp),
                                    color = bgColor
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            when (item) {
                                                DisplayItemType.WORK_HOURS -> "8.0h"
                                                DisplayItemType.OVERTIME_HOURS -> "1.5h"
                                                DisplayItemType.TOTAL_HOURS -> "9.5h"
                                                DisplayItemType.DAILY_INCOME -> "¥320"
                                                DisplayItemType.NORMAL_INCOME -> "¥256"
                                                DisplayItemType.OVERTIME_INCOME -> "¥64"
                                                DisplayItemType.SHIFT -> "班次"
                                                DisplayItemType.STATUS -> "状态"
                                            },
                                            fontSize = rowTextSize,
                                            lineHeight = rowTextSize,
                                            color = textColorForBackground(
                                                if (index == 0) rowConfig.backgroundColorLeft ?: "#E0E0E0"
                                                else rowConfig.backgroundColorRight ?: "#E0E0E0"
                                            ).let {
                                                try {
                                                    Color(android.graphics.Color.parseColor(it))
                                                } catch (_: Exception) {
                                                    MaterialTheme.colorScheme.onSurface
                                                }
                                            },
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // 空行占位
                        Spacer(modifier = Modifier.fillMaxWidth().height(dataRowHeight))
                    }
                }
            }
        }
    }
}

// ── 颜色选择器网格（2列4行，对应四行数据行的左右槽位）────────────────────────
@Composable
private fun ColorPickerGrid(
    dataRows: List<DataRowConfig>,
    onLeftColorChange: (Int, String?) -> Unit,
    onRightColorChange: (Int, String?) -> Unit,
    previewHeight: androidx.compose.ui.unit.Dp
) {
    var showPickerForSlot by remember { mutableStateOf<Pair<Int, Boolean>?>(null) } // Pair(rowIndex, isLeft)

    Column(
        modifier = Modifier
            .width(120.dp)
            .height(previewHeight),
        verticalArrangement = Arrangement.Top
    ) {
        // 农历行占位（2dp + 12dp = 14dp）
        Spacer(modifier = Modifier.height(14.dp))
        // 数据行间距占位（3dp）
        Spacer(modifier = Modifier.height(3.dp))
        // 四行颜色选择器（与数据行对齐）
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            dataRows.forEachIndexed { rowIndex, rowConfig ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 行标签
                    Text(
                        "${rowIndex + 1}",
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(10.dp)
                    )

                    // 左槽位颜色按钮
                    ColorSlotButton(
                        color = rowConfig.backgroundColorLeft,
                        enabled = rowConfig.items.any { it != null },
                        onClick = { showPickerForSlot = Pair(rowIndex, true) }
                    )

                    // 右槽位颜色按钮（仅当有2个数据项时显示）
                    if (rowConfig.items.count { it != null } >= 2) {
                        ColorSlotButton(
                            color = rowConfig.backgroundColorRight,
                            enabled = true,
                            onClick = { showPickerForSlot = Pair(rowIndex, false) }
                        )
                    } else {
                        // 空槽位占位
                        Spacer(modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    // 颜色选择器对话框
    showPickerForSlot?.let { (rowIndex, isLeft) ->
        val currentColor = if (isLeft) {
            dataRows.getOrNull(rowIndex)?.backgroundColorLeft
        } else {
            dataRows.getOrNull(rowIndex)?.backgroundColorRight
        }
        SimpleColorPicker(
            initialColor = currentColor,
            onColorSelected = { color ->
                if (isLeft) onLeftColorChange(rowIndex, color)
                else onRightColorChange(rowIndex, color)
                showPickerForSlot = null
            },
            onClear = {
                if (isLeft) onLeftColorChange(rowIndex, null)
                else onRightColorChange(rowIndex, null)
                showPickerForSlot = null
            },
            onDismiss = { showPickerForSlot = null }
        )
    }
}

// ── 单个颜色槽位按钮 ─────────────────────────────────────────────────────────
@Composable
private fun ColorSlotButton(
    color: String?,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = color?.let {
        try {
            Color(android.graphics.Color.parseColor(it))
        } catch (_: Exception) {
            MaterialTheme.colorScheme.surfaceVariant
        }
    } ?: MaterialTheme.colorScheme.surfaceVariant

    Surface(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(3.dp))
            .then(
                if (enabled) Modifier.clickable(onClick = onClick)
                else Modifier
            ),
        shape = RoundedCornerShape(3.dp),
        color = if (enabled) backgroundColor else backgroundColor.copy(alpha = 0.4f),
        border = BorderStroke(
            1.dp,
            if (enabled) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        )
    ) {
        if (color != null && enabled) {
            // 显示选中标记
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = textColorForBackground(color).let {
                        try {
                            Color(android.graphics.Color.parseColor(it))
                        } catch (_: Exception) {
                            Color.Black
                        }
                    }
                )
            }
        }
    }
}

// ── 增强版颜色选择器（支持清除）──────────────────────────────────────────────
@Composable
private fun SimpleColorPicker(
    initialColor: String?,
    onColorSelected: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = listOf(
        "#FFFFFF", "#F3F4F6", "#E5E7EB", "#D1D5DB",
        "#FEE2E2", "#FECACA", "#FCA5A5", "#F87171",
        "#FEF3C7", "#FDE68A", "#FCD34D", "#FBBF24",
        "#D1FAE5", "#A7F3D0", "#6EE7B7", "#34D399",
        "#DBEAFE", "#BFDBFE", "#93C5FD", "#60A5FA",
        "#E0E7FF", "#C7D2FE", "#A5B4FC", "#818CF8"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择背景色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.height(200.dp)
                ) {
                    items(colors) { color ->
                        val isSelected = color == initialColor
                        Surface(
                            onClick = { onColorSelected(color) },
                            modifier = Modifier
                                .size(32.dp)
                                .then(
                                    if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                    else Modifier
                                ),
                            shape = RoundedCornerShape(4.dp),
                            color = try {
                                Color(android.graphics.Color.parseColor(color))
                            } catch (_: Exception) {
                                Color.White
                            },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (color == "#FFFFFF") {
                                    Text("白", fontSize = 8.sp)
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
                // 清除按钮
                if (initialColor != null) {
                    OutlinedButton(
                        onClick = onClear,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        Text("清除颜色", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}
