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
                    text = "\u9009\u62e9\u5e74\u6708",
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
                        texts = yearList.map { "${it}\u5e74" },
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
                        texts = monthList.map { "${it}\u6708" },
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
                        Text("\u53d6\u6d88")
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
                        Text("\u786e\u8ba4", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
