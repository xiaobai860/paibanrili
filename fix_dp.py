import os

content = '''package com.schedulecalendar.app.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 稳定版年月选择器 - 使用 DropdownMenu 替代 WheelPicker
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

    var selectedYear by remember { mutableIntStateOf(currentYear) }
    var selectedMonth by remember { mutableIntStateOf(currentMonth) }

    var yearExpanded by remember { mutableStateOf(false) }
    var monthExpanded by remember { mutableStateOf(false) }

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
                    text = "\u9009\u62E9\u5E74\u6708",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text = "\u5E74\u4EFD\uFF1A", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(60.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(onClick = { yearExpanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                            Text(text = "${selectedYear}\u5E74", modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                            Spacer(Modifier.width(4.dp))
                            Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = yearExpanded, onDismissRequest = { yearExpanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
                            yearRange.forEach { year ->
                                DropdownMenuItem(text = {
                                    Text(text = "${year}\u5E74",
                                        fontWeight = if (year == selectedYear) FontWeight.Bold else FontWeight.Normal,
                                        color = if (year == selectedYear) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                }, onClick = { selectedYear = year; yearExpanded = false })
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text = "\u6708\u4EFD\uFF1A", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(60.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(onClick = { monthExpanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                            Text(text = "${selectedMonth}\u6708", modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                            Spacer(Modifier.width(4.dp))
                            Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = monthExpanded, onDismissRequest = { monthExpanded = false }) {
                            (1..12).forEach { month ->
                                DropdownMenuItem(text = {
                                    Text(text = "${month}\u6708",
                                        fontWeight = if (month == selectedMonth) FontWeight.Bold else FontWeight.Normal,
                                        color = if (month == selectedMonth) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                }, onClick = { selectedMonth = month; monthExpanded = false })
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Text("\u53D6\u6D88")
                    }
                    Button(onClick = { onConfirm(selectedYear.coerceIn(yearRange.first, yearRange.last), selectedMonth.coerceIn(1, 12)) },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Text("\u786E\u8BA4", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
'''

path = r"D:\Android\app-ckal0pi0u8sh\android_native\app\src\main\java\com\schedulecalendar\app\ui\component\DatePickerDialog.kt"
with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
print(f"Written {len(content)} bytes to {path}")
