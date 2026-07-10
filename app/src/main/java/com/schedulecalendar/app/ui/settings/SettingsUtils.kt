// app/src/main/java/com/schedulecalendar/app/ui/settings/SettingsUtils.kt
package com.schedulecalendar.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.schedulecalendar.app.ui.component.stableLabelColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 过滤金额/数字输入：只保留数字和小数点，多小数点只保留第一个 */
internal fun filterAmount(raw: String): String {
    val cleaned = raw.filter { ch -> ch.isDigit() || ch == '.' }
    val firstDot = cleaned.indexOf('.')
    return if (firstDot >= 0) {
        cleaned.substring(0, firstDot + 1) + cleaned.substring(firstDot + 1).replace(".", "")
    } else cleaned
}

/** 当 Double 值为 0.0 时返回空字符串（显示 placeholder），否则返回原始 toString */
internal fun Double.toInputString(): String = if (this == 0.0) "" else this.toString()
internal fun Int.toInputString(): String = if (this == 0) "" else this.toString()

/**
 * 带焦点保护的数字输入框 —— 防止 IME 在空字段聚焦时自动填入 "0"
 */
@Composable
internal fun ProtectedNumField(
    label: String,
    value: String,
    placeholder: String = "0",
    onValueChange: (String) -> Unit
) {
    var suppressImeAutoFill by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    OutlinedTextField(
        value = value,
        onValueChange = { newVal ->
            if (suppressImeAutoFill && newVal == "0") {
                suppressImeAutoFill = false
            } else {
                suppressImeAutoFill = false
                onValueChange(filterAmount(newVal))
            }
        },
        label = { Text(label) },
        placeholder = { Text(placeholder, color = Color(0xFFBBBBBB)) },
        modifier = Modifier.fillMaxWidth().onFocusChanged { fs ->
            if (fs.isFocused && value.isEmpty()) {
                suppressImeAutoFill = true
                scope.launch {
                    delay(150)
                    suppressImeAutoFill = false
                }
            }
        },
        singleLine = true,
        colors = stableLabelColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}
