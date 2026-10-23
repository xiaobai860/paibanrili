// app/src/main/java/com/schedulecalendar/app/ui/settings/ProfileSettings.kt
package com.schedulecalendar.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.schedulecalendar.app.domain.model.CalcProfile
import com.schedulecalendar.app.domain.util.DateFormats
import java.time.LocalDate

/**
 * 「计算口径档案」设置页共用组件。
 *
 * 交互模型：改配置**不再即时生效**，而是攒成草稿 → 选生效日 → 点「保存」生成一份新口径档案。
 * 旧口径保留 → 生效日之前的历史计算结果不受影响。
 */

/** 可选生效日：今天 / 下月 1 日（工资月结场景最常用）。普通函数：也可在 ViewModel/非组合上下文调用 */
fun effectiveDateOptions(): List<String> {
    val today = LocalDate.now()
    val nextMonth = today.plusMonths(1).withDayOfMonth(1)
    return listOf(DateFormats.date(today), DateFormats.date(nextMonth))
}

/** 生效日期选择行 */
@Composable
fun EffectiveDateRow(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = effectiveDateOptions()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "生效日期", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { date ->
                FilterChip(
                    selected = selected == date,
                    onClick = { onSelect(date) },
                    label = { Text(if (date == options.first()) "今天" else "下月 1 日") }
                )
            }
        }
        Text(
            "ⓘ $selected 之前的历史记录仍按原口径计算，不会被这次修改影响。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 当前生效口径（只读卡片）：让用户知道「现在按哪套算」 */
@Composable
fun CurrentProfileCard(profile: CalcProfile?, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "当前生效口径", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (profile == null) {
                Text(
                    "尚未初始化（暂时按当前设置值计算）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(profile.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "自 ${profile.effectiveFrom} 起生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "正常 ${profile.salary.normalRate} / 加班 ${profile.salary.overtimeRate} / " +
                        "周末 ${profile.salary.weekendRate} / 节假日 ${profile.salary.holidayRate}（元/时）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 底部保存栏：保存（生成新口径）/ 撤销修改 */
@Composable
fun ProfileSaveBar(
    enabled: Boolean,
    onSave: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onReset, enabled = enabled) { Text("撤销修改") }
        Button(onClick = onSave, enabled = enabled) { Text("保存（生成新口径）") }
    }
}

