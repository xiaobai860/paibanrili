// app/src/main/java/com/schedulecalendar/app/ui/detail/ExtraItemsScreen.kt
package com.schedulecalendar.app.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.domain.model.ExtraItem
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import com.schedulecalendar.app.ui.component.stableLabelColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ExtraItemsScreen(navController: NavController, vm: ExtraItemsViewModel = hiltViewModel()) {
    val items        by vm.items.collectAsStateWithLifecycle()
    var editTarget   by remember { mutableStateOf<ExtraItem?>(null) }
    var showEditor   by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ExtraItem?>(null) }
    val snackbarHost  = remember { SnackbarHostState() }

    // 一次性 UI 事件消费
    LaunchedEffect(vm) {
        vm.uiEvent.collect { event ->
            when (event) {
                is ExtraItemsUiEvent.ShowError -> snackbarHost.showSnackbar(event.msg)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = { ScheduleTopBar("附加项目（补贴/扣款）", onBack = { navController.popBackStack() }) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { editTarget = null; showEditor = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) { Icon(Icons.Filled.Add, "新增项目") }
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无附加项目", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showEditor = true }) { Text("添加第一个") }
                }
            }
        } else {
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val allowances = items.filter { it.type == "allowance" }
                val deductions = items.filter { it.type == "deduction" }

                if (allowances.isNotEmpty()) {
                    item { SectionLabel("补贴项目") }
                    items(allowances, key = { it.id }) { item ->
                        ExtraItemCard(item,
                            onEdit   = { editTarget = item; showEditor = true },
                            onDelete = { deleteTarget = item })
                    }
                }
                if (deductions.isNotEmpty()) {
                    item { SectionLabel("扣款项目") }
                    items(deductions, key = { it.id }) { item ->
                        ExtraItemCard(item,
                            onEdit   = { editTarget = item; showEditor = true },
                            onDelete = { deleteTarget = item })
                    }
                }
            }
        }
    }

    if (showEditor) {
        ExtraItemEditorDialog(
            item     = editTarget,
            existingNames = items.map { it.name },
            onSave   = { newItem ->
                if (editTarget != null) vm.saveAsReplacement(editTarget!!, newItem)
                else vm.save(newItem)
                showEditor = false; editTarget = null
            },
            onDismiss = { showEditor = false; editTarget = null }
        )
    }
    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除项目") },
            text  = { Text("确认删除「${item.name}」？\n已引用该项目的历史排班数据仍将保持不变。") },
            confirmButton = { TextButton(onClick = { vm.delete(item.id); deleteTarget = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun ExtraItemCard(item: ExtraItem, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "${if (item.type == "allowance") "+" else "-"}\u00a5${"%.2f".format(item.amount)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit)   { Icon(Icons.Filled.Edit,   "编辑", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtraItemEditorDialog(item: ExtraItem?, existingNames: List<String>, onSave: (ExtraItem) -> Unit, onDismiss: () -> Unit) {
    var name      by remember { mutableStateOf(item?.name    ?: "") }
    var amount    by remember { mutableStateOf(item?.amount?.takeIf { it > 0.0 }?.toString() ?: "") }
    var type      by remember { mutableStateOf(item?.type    ?: "allowance") }
    var nameError by remember { mutableStateOf<String?>(null) }
    // 焦点保护：防止 IME 在空字段聚焦时自动填入 "0"
    var suppressImeAutoFill by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "新增项目" else "编辑项目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it; nameError = null },
                    label = { Text("名称 *") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = nameError != null,
                    colors = stableLabelColors(),
                    supportingText = nameError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } })
                OutlinedTextField(
                    value = amount,
                    onValueChange = { newVal ->
                        if (suppressImeAutoFill && newVal == "0") {
                            // IME 自动填入的 "0"，直接丢弃，不更新 amount
                            suppressImeAutoFill = false
                        } else {
                            suppressImeAutoFill = false
                            // 只保留数字和小数点，过滤其他字符（如粘贴的货币符号）
                            val cleaned = newVal.filter { ch -> ch.isDigit() || ch == '.' }
                            // 多个小数点只保留第一个
                            val firstDot = cleaned.indexOf('.')
                            amount = if (firstDot >= 0) {
                                cleaned.substring(0, firstDot + 1) + cleaned.substring(firstDot + 1).replace(".", "")
                            } else cleaned
                        }
                    },
                    label = { Text("金额 (元)") },
                    placeholder = { Text("\u00a5 0.00", color = Color(0xFFBBBBBB)) },
                    singleLine = true,
                    colors = stableLabelColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { fs ->
                        // 聚焦时且字段为空：开启 150ms 保护窗口
                        if (fs.isFocused && amount.isEmpty()) {
                            suppressImeAutoFill = true
                            scope.launch {
                                delay(150) // 覆盖 IME 自动填入的时间窗口
                                suppressImeAutoFill = false
                            }
                        }
                    }
                )

                Text("类型", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("allowance" to "补贴", "deduction" to "扣款").forEach { (v, label) ->
                        FilterChip(selected = type == v, onClick = { type = v }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    nameError = null
                    val trimmed = name.trim()
                    if (trimmed.isBlank()) { nameError = "\u540d\u79f0\u4e0d\u80fd\u4e3a\u7a7a"; return@TextButton }
                    val dup = existingNames.any { it.equals(trimmed, ignoreCase = true) && (item == null || !it.equals(item.name, ignoreCase = true)) }
                    if (dup) { nameError = "\u540d\u79f0\u5df2\u5b58\u5728\uff0c\u8bf7\u4fee\u6539\u540e\u4fdd\u5b58"; return@TextButton }
                    onSave(ExtraItem(
                        id = item?.id ?: UUID.randomUUID().toString(),
                        name = trimmed, type = type,
                        amount = amount.toDoubleOrNull() ?: 0.0
                    ))
                }
            ) { Text("\u4fdd\u5b58") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}